package com.telerad.images;

import com.telerad.audit.AuditService;
import com.telerad.auth.UserPrincipal;
import com.telerad.common.CloudStatus;
import com.telerad.common.Role;
import com.telerad.common.UploadPolicy;
import com.telerad.gateway.Gateway;
import com.telerad.gateway.GatewayRepository;
import com.telerad.share.SharePrincipal;
import com.telerad.storage.ObjectStorageService;
import com.telerad.study.Instance;
import com.telerad.study.InstanceRepository;
import com.telerad.study.Study;
import com.telerad.study.StudyRepository;
import com.telerad.tenant.Tenant;
import com.telerad.tenant.TenantRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Where a study's image files are, and moving the cloud copy (KB note 13).
 * The hospital server (edge gateway) holds the images; the cloud holds the index of every study
 * and a copy of the files while a remote read needs them.
 */
@Service
@Slf4j
public class ImageLocationService {
    static final int MAX_UPLOADS_PER_POLL = 20;

    private final StudyRepository studyRepository;
    private final InstanceRepository instanceRepository;
    private final TenantRepository tenantRepository;
    private final GatewayRepository gatewayRepository;
    private final ObjectStorageService storage;
    private final AuditService auditService;
    private final ZoneId facilityZone;

    public ImageLocationService(StudyRepository studyRepository, InstanceRepository instanceRepository,
                                TenantRepository tenantRepository, GatewayRepository gatewayRepository,
                                ObjectStorageService storage, AuditService auditService,
                                @Value("${telerad.images.facility-zone:Africa/Addis_Ababa}") String facilityZone) {
        this.studyRepository = studyRepository;
        this.instanceRepository = instanceRepository;
        this.tenantRepository = tenantRepository;
        this.gatewayRepository = gatewayRepository;
        this.storage = storage;
        this.auditService = auditService;
        this.facilityZone = ZoneId.of(facilityZone);
    }

    public record ImageAccess(
            CloudStatus cloudStatus,
            /** The hospital server holds the image files. */
            boolean onHospitalServer,
            UUID gatewayId,
            /** Address of the hospital server's image service on the hospital network. */
            String lanUrl,
            long cloudInstances,
            int totalInstances,
            Instant cloudRequestedAt
    ) {}

    public record UploadRequest(String studyInstanceUid) {}

    public record VerifyRequest(String studyInstanceUid, int instanceCount) {}

    public record Work(List<UploadRequest> uploads, List<VerifyRequest> verify) {}

    // ------------------------------------------------------------------ viewer

    @Transactional(readOnly = true)
    public ImageAccess describe(UUID studyId) {
        Study study = requireViewable(studyId);
        return describe(study);
    }

    ImageAccess describe(Study study) {
        String lanUrl = null;
        if (study.getEdgeGatewayId() != null) {
            lanUrl = gatewayRepository.findById(study.getEdgeGatewayId())
                    .filter(Gateway::isActive)
                    .map(Gateway::getLanUrl)
                    .orElse(null);
        }
        return new ImageAccess(
                study.getCloudStatus(),
                onHospitalServer(study),
                study.getEdgeGatewayId(),
                lanUrl,
                instanceRepository.countWithCloudCopy(study.getId()),
                study.getInstanceCount(),
                study.getCloudRequestedAt());
    }

    public static boolean onHospitalServer(Study s) {
        return s.getEdgeGatewayId() != null && s.getEdgeEvictedAt() == null;
    }

    /** A study the current user may open (same rule as the viewer). */
    @Transactional(readOnly = true)
    public Study requireViewable(UUID studyId) {
        if (SharePrincipal.currentOrNull() != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        Study study = studyRepository.findById(studyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Study not found"));
        UserPrincipal user = UserPrincipal.current();
        if (user.getRole() == Role.ADMIN || user.getRole() == Role.RADIOLOGIST) return study;
        if (user.getRole() == Role.HOSPITAL_USER && study.getTenantId().equals(user.getTenantId())) return study;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    // ------------------------------------------------------------------ requesting a cloud copy

    /**
     * "Send for remote reading" (hospital staff) or "Request images" (radiologist): asks the
     * hospital server to upload the study, plus the facility's number of priors.
     */
    @Transactional
    public ImageAccess requestCloudCopy(UUID studyId) {
        Study study = requireViewable(studyId);
        UserPrincipal user = UserPrincipal.current();
        if (study.getEdgeGatewayId() == null) {
            return describe(study); // uploaded straight to the cloud; nothing to fetch
        }
        if (study.getCloudStatus() == CloudStatus.EDGE_ONLY && study.getEdgeEvictedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "The hospital server no longer holds this study");
        }
        Tenant tenant = tenantRepository.findById(study.getTenantId()).orElseThrow();
        boolean asked = request(study, user.getId());
        int priors = requestPriors(study, tenant, user.getId());
        auditService.record(study.getTenantId(), user.getId(), "CLOUD_COPY_REQUEST", "STUDY",
                study.getId().toString(), "asked=" + asked + ", priors=" + priors, null);
        return describe(study);
    }

    /** @return true when the hospital server now has to upload something */
    boolean request(Study s, UUID by) {
        if (s.getEdgeGatewayId() == null) return false;
        Instant now = Instant.now();
        switch (s.getCloudStatus()) {
            case AVAILABLE -> {
                s.setCloudRequestedAt(now); // keeps the copy N more days
                studyRepository.save(s);
                return false;
            }
            case PENDING_DELETE -> {
                s.setCloudStatus(CloudStatus.AVAILABLE); // files are still there: cancel the removal
                s.setCloudRequestedAt(now);
                studyRepository.save(s);
                return false;
            }
            case REQUESTED -> {
                return false;
            }
            case EDGE_ONLY -> {
                if (s.getEdgeEvictedAt() != null) return false;
                s.setCloudStatus(CloudStatus.REQUESTED);
                s.setCloudRequestedAt(now);
                s.setCloudRequestedBy(by);
                studyRepository.save(s);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    int requestPriors(Study study, Tenant tenant, UUID by) {
        int wanted = Math.max(0, tenant.getPriorsToSend());
        if (wanted == 0 || study.getPatientId() == null) return 0;
        int count = 0;
        for (Study prior : studyRepository.findPriors(study.getPatientId(), study.getTenantId(), study.getId(),
                study.getModality())) {
            if (count >= wanted) break;
            if (!isEarlier(prior, study)) continue;
            request(prior, by);
            count++;
        }
        return count;
    }

    private static boolean isEarlier(Study candidate, Study current) {
        if (candidate.getStudyDate() != null && current.getStudyDate() != null
                && !candidate.getStudyDate().isEqual(current.getStudyDate())) {
            return candidate.getStudyDate().isBefore(current.getStudyDate());
        }
        return candidate.getCreatedAt().isBefore(current.getCreatedAt());
    }

    // ------------------------------------------------------------------ hospital server callbacks

    /**
     * After the hospital server indexed (part of) a study: the rows exist, the files may not.
     * On the last batch, the facility's automatic rules may request a cloud copy.
     */
    @Transactional
    public void afterIndexed(Gateway gateway, UUID tenantId, String studyUid, boolean studyComplete) {
        Study s = studyRepository.findByTenantIdAndStudyInstanceUid(tenantId, studyUid).orElse(null);
        if (s == null) return;
        s.setEdgeGatewayId(gateway.getId());
        s.setEdgeEvictedAt(null);
        long withoutCopy = instanceRepository.countWithoutCloudCopy(s.getId());
        if (withoutCopy > 0 && (s.getCloudStatus() == CloudStatus.AVAILABLE
                || s.getCloudStatus() == CloudStatus.PENDING_DELETE)) {
            // New images in a study the cloud already has follow it up; a new study stays local.
            boolean hasCloudCopy = instanceRepository.countWithCloudCopy(s.getId()) > 0;
            s.setCloudStatus(hasCloudCopy ? CloudStatus.REQUESTED : CloudStatus.EDGE_ONLY);
            if (hasCloudCopy) s.setCloudRequestedAt(Instant.now());
        }
        studyRepository.save(s);

        if (studyComplete && s.getCloudStatus() == CloudStatus.EDGE_ONLY && s.getCloudRequestedAt() == null) {
            Tenant tenant = tenantRepository.findById(tenantId).orElseThrow();
            boolean all = tenant.getUploadPolicy() == UploadPolicy.ALL;
            boolean rule = !all && AutoSendRules.matches(AutoSendRules.parse(tenant.getAutoSendRules()),
                    s.getModality(), s.getPriority(), ZonedDateTime.now(facilityZone));
            if (all || rule) {
                request(s, null);
                int priors = requestPriors(s, tenant, null);
                auditService.record(tenantId, null, "CLOUD_COPY_REQUEST", "STUDY", s.getId().toString(),
                        (all ? "policy=ALL" : "rule") + ", priors=" + priors, null);
            }
        }
    }

    /** After the hospital server uploaded image files of a study. */
    @Transactional
    public void afterUploaded(Gateway gateway, UUID tenantId, String studyUid) {
        Study s = studyRepository.findByTenantIdAndStudyInstanceUid(tenantId, studyUid).orElse(null);
        if (s == null) return;
        if (s.getEdgeGatewayId() == null) s.setEdgeGatewayId(gateway.getId());
        s.setEdgeEvictedAt(null);
        if (instanceRepository.countWithoutCloudCopy(s.getId()) == 0) {
            if (s.getCloudStatus() != CloudStatus.AVAILABLE) {
                s.setCloudStatus(CloudStatus.AVAILABLE);
                s.setCloudAvailableAt(Instant.now());
                s.setCloudDeletedAt(null);
            } else if (s.getCloudAvailableAt() == null) {
                s.setCloudAvailableAt(Instant.now());
            }
        } else if (s.getCloudStatus() == CloudStatus.EDGE_ONLY) {
            s.setCloudStatus(CloudStatus.REQUESTED); // partly uploaded: the rest is on its way
        }
        studyRepository.save(s);
    }

    /** What the hospital server should do now: uploads, and studies to confirm before removal. */
    @Transactional(readOnly = true)
    public Work work(Gateway gateway) {
        List<UploadRequest> uploads = studyRepository
                .findByEdgeGatewayIdAndCloudStatusOrderByCloudRequestedAtAsc(gateway.getId(), CloudStatus.REQUESTED)
                .stream().limit(MAX_UPLOADS_PER_POLL)
                .map(s -> new UploadRequest(s.getStudyInstanceUid())).toList();
        List<VerifyRequest> verify = studyRepository
                .findByEdgeGatewayIdAndCloudStatusOrderByCloudRequestedAtAsc(gateway.getId(), CloudStatus.PENDING_DELETE)
                .stream().limit(MAX_UPLOADS_PER_POLL)
                .map(s -> new VerifyRequest(s.getStudyInstanceUid(), s.getInstanceCount())).toList();
        return new Work(uploads, verify);
    }

    /**
     * The hospital server's answer for a study waiting for removal: it holds {@code present}
     * instances. The cloud copy is removed only when it holds them all.
     */
    @Transactional
    public void confirmHeld(Gateway gateway, String studyUid, int present) {
        Study s = studyRepository.findByTenantIdAndStudyInstanceUid(gateway.getTenantId(), studyUid).orElse(null);
        if (s == null || !gateway.getId().equals(s.getEdgeGatewayId())
                || s.getCloudStatus() != CloudStatus.PENDING_DELETE) {
            return;
        }
        if (present >= s.getInstanceCount() && s.getInstanceCount() > 0) {
            deleteCloudCopy(s);
            return;
        }
        // Keep the cloud copy: it may be the only one left.
        s.setCloudStatus(CloudStatus.AVAILABLE);
        s.setCloudRequestedAt(Instant.now()); // try again after another retention period
        if (present == 0) s.setEdgeEvictedAt(Instant.now());
        studyRepository.save(s);
        log.warn("Kept cloud copy of study {}: hospital server holds {} of {} images",
                s.getId(), present, s.getInstanceCount());
        auditService.record(s.getTenantId(), null, "CLOUD_COPY_KEPT", "STUDY", s.getId().toString(),
                "hospital server holds " + present + " of " + s.getInstanceCount(), null);
    }

    /** The hospital server removed these studies (its local retention). */
    @Transactional
    public void markEvicted(Gateway gateway, List<String> studyUids) {
        for (String uid : studyUids == null ? List.<String>of() : studyUids) {
            Study s = studyRepository.findByTenantIdAndStudyInstanceUid(gateway.getTenantId(), uid).orElse(null);
            if (s == null || !gateway.getId().equals(s.getEdgeGatewayId())) continue;
            s.setEdgeEvictedAt(Instant.now());
            if (s.getCloudStatus() == CloudStatus.REQUESTED) {
                s.setCloudStatus(instanceRepository.countWithCloudCopy(s.getId()) > 0
                        ? CloudStatus.AVAILABLE : CloudStatus.EDGE_ONLY);
            }
            studyRepository.save(s);
            auditService.record(s.getTenantId(), null, "EDGE_COPY_REMOVED", "STUDY", s.getId().toString(),
                    "hospital server retention", null);
        }
    }

    // ------------------------------------------------------------------ cloud retention

    /**
     * Marks cloud copies past the facility's retention (days after the report was signed, or
     * after the last request). They are removed once the hospital server confirms it holds them.
     */
    @Scheduled(fixedDelayString = "${telerad.images.retention-check-ms:3600000}",
            initialDelayString = "${telerad.images.retention-initial-delay-ms:120000}")
    @Transactional
    public int markExpiredCloudCopies() {
        int marked = 0;
        for (Tenant tenant : tenantRepository.findAll()) {
            Integer days = tenant.getCloudRetentionDays();
            if (days == null || days < 0) continue;
            Instant cutoff = Instant.now().minus(Duration.ofDays(days));
            for (Study s : studyRepository.findCloudRetentionCandidates(tenant.getId(), cutoff)) {
                s.setCloudStatus(CloudStatus.PENDING_DELETE);
                studyRepository.save(s);
                marked++;
            }
        }
        if (marked > 0) log.info("Cloud retention: {} studies waiting for hospital server confirmation", marked);
        return marked;
    }

    private void deleteCloudCopy(Study s) {
        List<String> keys = new ArrayList<>();
        for (Instance inst : instanceRepository.findByStudyId(s.getId())) {
            if (inst.getStorageKey() != null) keys.add(inst.getStorageKey());
            if (inst.getGzipStorageKey() != null) keys.add(inst.getGzipStorageKey());
            if (inst.getThumbnailKey() != null) keys.add(inst.getThumbnailKey());
            inst.setStorageKey(null);
            inst.setGzipStorageKey(null);
            inst.setThumbnailKey(null);
            inst.setEncodingsChecked(false); // a later upload makes the gzip copy again
            instanceRepository.save(inst);
        }
        s.setCloudStatus(CloudStatus.EDGE_ONLY);
        s.setCloudDeletedAt(Instant.now());
        s.setCloudAvailableAt(null);
        studyRepository.save(s);
        auditService.record(s.getTenantId(), null, "CLOUD_COPY_DELETED", "STUDY", s.getId().toString(),
                keys.size() + " objects", null);
        // Rows first, objects after commit: a failed transaction never leaves rows pointing at
        // deleted objects (a failed delete only leaves an orphan object).
        afterCommit(() -> {
            int failed = 0;
            for (String key : keys) {
                try {
                    storage.delete(key);
                } catch (Exception e) {
                    failed++;
                }
            }
            if (failed > 0) log.warn("Cloud copy of study {}: {} objects could not be deleted", s.getId(), failed);
        });
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
