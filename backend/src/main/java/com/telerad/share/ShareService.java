package com.telerad.share;

import com.telerad.audit.AuditService;
import com.telerad.auth.JwtService;
import com.telerad.auth.UserPrincipal;
import com.telerad.common.Role;
import com.telerad.common.StudyStatus;
import com.telerad.common.ReportStatus;
import com.telerad.report.Report;
import com.telerad.report.ReportRepository;
import com.telerad.storage.ObjectStorageService;
import com.telerad.study.*;
import com.telerad.tenant.Tenant;
import com.telerad.tenant.TenantRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ShareService {
    private static final List<StudyStatus> SNAPSHOT_STATUSES = List.of(
            StudyStatus.READY, StudyStatus.IN_PROGRESS, StudyStatus.REPORTED, StudyStatus.DELIVERED
    );
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int LOCKOUT_MINUTES = 15;
    private static final int IP_RATE_LIMIT = 30;
    private static final long IP_RATE_WINDOW_MS = 60_000L;

    private final PatientShareRepository shareRepository;
    private final PatientShareStudyRepository shareStudyRepository;
    private final StudyRepository studyRepository;
    private final SeriesQueryRepository seriesQueryRepository;
    private final InstanceRepository instanceRepository;
    private final PatientRepository patientRepository;
    private final TenantRepository tenantRepository;
    private final ReportRepository reportRepository;
    private final StudyService studyService;
    private final ObjectStorageService storage;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService auditService;
    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentHashMap<String, RateBucket> ipBuckets = new ConcurrentHashMap<>();

    @Value("${telerad.share.public-base-url:http://localhost:5173}")
    private String publicBaseUrl;

    @Value("${telerad.share.default-expiry-days:60}")
    private int defaultExpiryDays;

    public record CreateRequest(
            UUID patientId,
            UUID studyId,
            boolean consentConfirmed,
            Integer expiryDays,
            Boolean includeReports,
            Boolean allowDicomDownload
    ) {}

    public record CreateResult(
            UUID shareId,
            String url,
            String accessCode,
            Instant expiresAt,
            UUID mainStudyId,
            int priorCount,
            int studyCount,
            String facilityName,
            String patientLabel,
            String modality,
            String studyDescription,
            String studyDate
    ) {}

    public record ShareListItem(
            UUID id,
            UUID patientId,
            UUID mainStudyId,
            String patientName,
            Instant createdAt,
            Instant expiresAt,
            Instant revokedAt,
            int studyCount,
            int priorCount,
            boolean includeReports,
            boolean allowDicomDownload,
            boolean active
    ) {}

    public record PublicMeta(
            String facilityName,
            boolean expired,
            boolean revoked,
            boolean locked,
            Instant expiresAt
    ) {}

    public record UnlockResult(
            String accessToken,
            Instant expiresAt,
            String facilityName,
            boolean includeReports,
            boolean allowDicomDownload,
            UUID mainStudyId,
            int priorCount,
            int studyCount
    ) {}

    public record ShareStudyItem(
            UUID id,
            String studyInstanceUid,
            String modality,
            String studyDescription,
            String studyDate,
            String status,
            int instanceCount,
            boolean hasSignedReport,
            UUID signedReportId
    ) {}

    public record ShareInstanceDto(
            UUID id, String sopInstanceUid, Integer instanceNumber,
            Integer rows, Integer columns, int numberOfFrames
    ) {}

    public record ShareSeriesDto(
            UUID id, String seriesInstanceUid, String modality, Integer seriesNumber,
            String seriesDescription, int instanceCount, List<ShareInstanceDto> instances
    ) {}

    public record ShareStudyDetail(ShareStudyItem study, List<ShareSeriesDto> series) {}

    @Transactional
    public CreateResult create(CreateRequest req, HttpServletRequest http) {
        if (req == null || !req.consentConfirmed()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Patient consent confirmation is required");
        }
        if (req.studyId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "studyId is required");
        }
        UserPrincipal user = UserPrincipal.current();
        Study main = studyRepository.findById(req.studyId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Study not found"));
        if (main.getPatientId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Study has no patient");
        }
        if (!SNAPSHOT_STATUSES.contains(main.getStatus()) || main.getInstanceCount() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Study is not READY+ with images");
        }
        Patient patient = patientRepository.findById(main.getPatientId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Patient not found"));
        assertCanManageShares(user, patient.getTenantId());

        List<Study> priors = studyService.findPriorStudiesForSnapshot(main);

        int expiryDays = req.expiryDays() == null ? defaultExpiryDays : Math.max(1, Math.min(req.expiryDays(), 365));
        boolean includeReports = req.includeReports() == null || req.includeReports();
        boolean allowDicom = req.allowDicomDownload() != null && req.allowDicomDownload();

        String rawToken = randomToken(32);
        String rawCode = randomAccessCode(7);
        Instant now = Instant.now();

        PatientShare share = PatientShare.builder()
                .tenantId(patient.getTenantId())
                .patientId(patient.getId())
                .mainStudyId(main.getId())
                .createdBy(user.getId())
                .consentAt(now)
                .consentBy(user.getId())
                .linkTokenLookup(sha256Hex(rawToken))
                .accessCodeHash(passwordEncoder.encode(rawCode))
                .expiresAt(now.plus(expiryDays, ChronoUnit.DAYS))
                .includeReports(includeReports)
                .allowDicomDownload(allowDicom)
                .build();
        share = shareRepository.save(share);

        shareStudyRepository.save(PatientShareStudy.builder()
                .shareId(share.getId())
                .studyId(main.getId())
                .role("MAIN")
                .build());
        for (Study prior : priors) {
            shareStudyRepository.save(PatientShareStudy.builder()
                    .shareId(share.getId())
                    .studyId(prior.getId())
                    .role("PRIOR")
                    .build());
        }

        String facility = tenantRepository.findById(patient.getTenantId()).map(Tenant::getName).orElse("Facility");
        auditService.record(patient.getTenantId(), user.getId(), "SHARE_CREATE", "PatientShare",
                share.getId().toString(),
                "main=" + main.getId() + ",priors=" + priors.size() + ",consent=true",
                clientIp(http));

        String url = publicBaseUrl.replaceAll("/$", "") + "/s/" + rawToken;
        return new CreateResult(
                share.getId(),
                url,
                rawCode,
                share.getExpiresAt(),
                main.getId(),
                priors.size(),
                1 + priors.size(),
                facility,
                patient.getPatientName(),
                main.getModality(),
                main.getStudyDescription(),
                main.getStudyDate() == null ? null : main.getStudyDate().toString()
        );
    }

    @Transactional(readOnly = true)
    public List<ShareListItem> list(UUID patientId) {
        UserPrincipal user = UserPrincipal.current();
        List<PatientShare> rows;
        if (patientId != null) {
            Patient patient = patientRepository.findById(patientId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            assertCanManageShares(user, patient.getTenantId());
            rows = shareRepository.findByTenantIdAndPatientIdOrderByCreatedAtDesc(patient.getTenantId(), patientId);
        } else {
            UUID tenantId = resolveListTenant(user);
            assertCanManageShares(user, tenantId);
            rows = shareRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
        }
        return rows.stream().map(this::toListItem).toList();
    }

    @Transactional
    public ShareListItem revoke(UUID shareId, HttpServletRequest http) {
        PatientShare share = requireManageable(shareId);
        if (share.getRevokedAt() == null) {
            share.setRevokedAt(Instant.now());
            shareRepository.save(share);
            auditService.record(share.getTenantId(), UserPrincipal.current().getId(), "SHARE_REVOKE",
                    "PatientShare", shareId.toString(), null, clientIp(http));
        }
        return toListItem(share);
    }

    @Transactional
    public Map<String, Object> regenerateCode(UUID shareId, HttpServletRequest http) {
        PatientShare share = requireManageable(shareId);
        if (!share.isUsable()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Share is not active");
        }
        String rawCode = randomAccessCode(7);
        share.setAccessCodeHash(passwordEncoder.encode(rawCode));
        share.setCodeVersion(share.getCodeVersion() + 1);
        share.setFailedAttempts(0);
        share.setLockedUntil(null);
        shareRepository.save(share);
        auditService.record(share.getTenantId(), UserPrincipal.current().getId(), "SHARE_REGEN_CODE",
                "PatientShare", shareId.toString(), "codeVersion=" + share.getCodeVersion(), clientIp(http));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("shareId", share.getId());
        out.put("accessCode", rawCode);
        out.put("expiresAt", share.getExpiresAt());
        return out;
    }

    /** Public: non-PHI metadata. Uses linear scan of candidates by verifying token hash. */
    @Transactional(readOnly = true)
    public PublicMeta publicMeta(String rawToken) {
        PatientShare share = findByRawToken(rawToken)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Share not found"));
        String facility = tenantRepository.findById(share.getTenantId()).map(Tenant::getName).orElse("Facility");
        return new PublicMeta(facility, share.isExpired(), share.isRevoked(), share.isLocked(), share.getExpiresAt());
    }

    @Transactional
    public UnlockResult unlock(String rawToken, String accessCode, HttpServletRequest http) {
        String ip = clientIp(http);
        checkIpRateLimit(ip);
        PatientShare share = findByRawToken(rawToken)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Share not found"));
        if (share.isRevoked() || share.isExpired()) {
            auditService.record(share.getTenantId(), null, "SHARE_UNLOCK_FAIL", "PatientShare",
                    share.getId().toString(), share.isRevoked() ? "revoked" : "expired", ip);
            throw new ResponseStatusException(HttpStatus.GONE, share.isRevoked() ? "Share revoked" : "Share expired");
        }
        if (share.isLocked()) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts; try again later");
        }
        if (accessCode == null || accessCode.isBlank()
                || !passwordEncoder.matches(accessCode.trim().toUpperCase(Locale.ROOT), share.getAccessCodeHash())) {
            share.setFailedAttempts(share.getFailedAttempts() + 1);
            if (share.getFailedAttempts() >= MAX_FAILED_ATTEMPTS) {
                share.setLockedUntil(Instant.now().plus(LOCKOUT_MINUTES, ChronoUnit.MINUTES));
                share.setFailedAttempts(0);
            }
            shareRepository.save(share);
            auditService.record(share.getTenantId(), null, "SHARE_UNLOCK_FAIL", "PatientShare",
                    share.getId().toString(), "bad_code", ip);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid access code");
        }
        share.setFailedAttempts(0);
        share.setLockedUntil(null);
        shareRepository.save(share);
        String jwt = jwtService.createShareToken(
                share.getId(),
                share.getTenantId(),
                share.getPatientId(),
                share.getCodeVersion(),
                share.isIncludeReports(),
                share.isAllowDicomDownload(),
                share.getExpiresAt()
        );
        auditService.record(share.getTenantId(), null, "SHARE_UNLOCK_OK", "PatientShare",
                share.getId().toString(), null, ip);
        String facility = tenantRepository.findById(share.getTenantId()).map(Tenant::getName).orElse("Facility");
        UUID mainStudyId = resolveMainStudyId(share);
        int priorCount = shareStudyRepository.findStudyIdsByShareIdAndRole(share.getId(), "PRIOR").size();
        int studyCount = shareStudyRepository.findStudyIdsByShareId(share.getId()).size();
        return new UnlockResult(
                jwt,
                share.getExpiresAt(),
                facility,
                share.isIncludeReports(),
                share.isAllowDicomDownload(),
                mainStudyId,
                priorCount,
                studyCount
        );
    }

    @Transactional(readOnly = true)
    public List<ShareStudyItem> listMyStudies(HttpServletRequest http) {
        SharePrincipal sp = requireActiveShare();
        List<UUID> ids = shareStudyRepository.findStudyIdsByShareId(sp.getShareId());
        Map<UUID, Study> byId = studyRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Study::getId, s -> s));
        List<ShareStudyItem> out = new ArrayList<>();
        for (UUID id : ids) {
            Study s = byId.get(id);
            if (s == null) continue;
            out.add(toShareStudyItem(s, sp.isIncludeReports()));
        }
        return out;
    }

    public record ShareSessionInfo(
            UUID mainStudyId,
            String facilityName,
            boolean includeReports,
            boolean allowDicomDownload,
            int priorCount,
            int studyCount
    ) {}

    @Transactional(readOnly = true)
    public ShareSessionInfo mySession() {
        SharePrincipal sp = requireActiveShare();
        PatientShare share = requireActiveShareEntity(sp.getShareId());
        String facility = tenantRepository.findById(share.getTenantId()).map(Tenant::getName).orElse("Facility");
        return new ShareSessionInfo(
                resolveMainStudyId(share),
                facility,
                share.isIncludeReports(),
                share.isAllowDicomDownload(),
                shareStudyRepository.findStudyIdsByShareIdAndRole(share.getId(), "PRIOR").size(),
                shareStudyRepository.findStudyIdsByShareId(share.getId()).size()
        );
    }

    /** Full study detail (same shape as staff viewer) for a frozen snapshot study. */
    @Transactional(readOnly = true)
    public StudyService.StudyDetail getMyViewerStudy(UUID studyId, HttpServletRequest http) {
        SharePrincipal sp = requireActiveShare();
        requireStudyInShare(sp.getShareId(), studyId);
        auditService.record(sp.getTenantId(), null, "SHARE_VIEW_STUDY", "Study", studyId.toString(), null, clientIp(http));
        StudyService.StudyDetail detail = studyService.detailWithoutAuth(studyId);
        if (!sp.isIncludeReports() && detail.report() != null) {
            return new StudyService.StudyDetail(detail.study(), detail.series(), null);
        }
        return detail;
    }

    /** Frozen PRIOR studies only (for Compare picker). */
    @Transactional(readOnly = true)
    public List<StudyService.StudyListItem> listMyFrozenPriors(UUID studyId, HttpServletRequest http) {
        SharePrincipal sp = requireActiveShare();
        requireStudyInShare(sp.getShareId(), studyId);
        List<UUID> priorIds = shareStudyRepository.findStudyIdsByShareIdAndRole(sp.getShareId(), "PRIOR");
        List<StudyService.StudyListItem> out = new ArrayList<>();
        for (UUID id : priorIds) {
            out.add(studyService.listItemWithoutAuth(id));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public byte[] downloadSignedPdf(UUID studyId, HttpServletRequest http) {
        SharePrincipal sp = requireActiveShare();
        if (!sp.isIncludeReports()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Reports not included in this share");
        }
        requireStudyInShare(sp.getShareId(), studyId);
        Report report = reportRepository.findByStudyId(studyId)
                .filter(r -> r.getStatus() == ReportStatus.SIGNED && r.getPdfStorageKey() != null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Signed report PDF not available"));
        auditService.record(sp.getTenantId(), null, "SHARE_DOWNLOAD_PDF", "Report",
                report.getId().toString(), null, clientIp(http));
        return storage.getBytes(report.getPdfStorageKey());
    }

    public void assertShareCanViewStudy(UUID studyId) {
        SharePrincipal sp = requireActiveShare();
        requireStudyInShare(sp.getShareId(), studyId);
    }

    public boolean shareContainsStudy(UUID shareId, UUID studyId) {
        return shareStudyRepository.existsByShareIdAndStudyId(shareId, studyId);
    }

    public PatientShare requireActiveShareEntity(UUID shareId) {
        PatientShare share = shareRepository.findById(shareId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (!share.isUsable()) {
            throw new ResponseStatusException(HttpStatus.GONE, "Share not active");
        }
        return share;
    }

    private ShareStudyItem toShareStudyItem(Study s, boolean includeReports) {
        UUID reportId = null;
        boolean has = false;
        if (includeReports) {
            Optional<Report> r = reportRepository.findByStudyId(s.getId())
                    .filter(rep -> rep.getStatus() == ReportStatus.SIGNED && rep.getPdfStorageKey() != null);
            if (r.isPresent()) {
                has = true;
                reportId = r.get().getId();
            }
        }
        return new ShareStudyItem(
                s.getId(),
                s.getStudyInstanceUid(),
                s.getModality(),
                s.getStudyDescription(),
                s.getStudyDate() == null ? null : s.getStudyDate().toString(),
                s.getStatus().name(),
                s.getInstanceCount(),
                has,
                reportId
        );
    }

    private ShareListItem toListItem(PatientShare share) {
        Patient p = patientRepository.findById(share.getPatientId()).orElse(null);
        int count = shareStudyRepository.findStudyIdsByShareId(share.getId()).size();
        int priorCount = shareStudyRepository.findStudyIdsByShareIdAndRole(share.getId(), "PRIOR").size();
        boolean active = share.isUsable();
        return new ShareListItem(
                share.getId(),
                share.getPatientId(),
                resolveMainStudyId(share),
                p == null ? null : p.getPatientName(),
                share.getCreatedAt(),
                share.getExpiresAt(),
                share.getRevokedAt(),
                count,
                priorCount,
                share.isIncludeReports(),
                share.isAllowDicomDownload(),
                active
        );
    }

    private UUID resolveMainStudyId(PatientShare share) {
        if (share.getMainStudyId() != null) {
            return share.getMainStudyId();
        }
        return shareStudyRepository.findByShareIdAndRole(share.getId(), "MAIN")
                .map(PatientShareStudy::getStudyId)
                .or(() -> shareStudyRepository.findStudyIdsByShareId(share.getId()).stream().findFirst())
                .orElse(null);
    }

    private PatientShare requireManageable(UUID shareId) {
        PatientShare share = shareRepository.findById(shareId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        assertCanManageShares(UserPrincipal.current(), share.getTenantId());
        return share;
    }

    private void assertCanManageShares(UserPrincipal user, UUID tenantId) {
        if (user.getRole() == Role.ADMIN) return;
        if (user.getRole() == Role.RADIOLOGIST) return; // may create for any facility's patient they act on; tenant checked via patient
        if (user.getRole() == Role.HOSPITAL_USER && tenantId.equals(user.getTenantId())) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed to manage shares for this facility");
    }

    private UUID resolveListTenant(UserPrincipal user) {
        if (user.getRole() == Role.HOSPITAL_USER) {
            if (user.getTenantId() == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
            return user.getTenantId();
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "patientId required");
    }

    private SharePrincipal requireActiveShare() {
        SharePrincipal sp = SharePrincipal.currentOrNull();
        if (sp == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Share session required");
        }
        PatientShare share = requireActiveShareEntity(sp.getShareId());
        if (share.getCodeVersion() != sp.getCodeVersion()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Access code was regenerated; unlock again");
        }
        return sp;
    }

    private void requireStudyInShare(UUID shareId, UUID studyId) {
        if (!shareStudyRepository.existsByShareIdAndStudyId(shareId, studyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Study not in this share");
        }
    }

    private Optional<PatientShare> findByRawToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return Optional.empty();
        return shareRepository.findByLinkTokenLookup(sha256Hex(rawToken));
    }

    private static String sha256Hex(String value) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void checkIpRateLimit(String ip) {
        long now = System.currentTimeMillis();
        RateBucket b = ipBuckets.computeIfAbsent(ip == null ? "unknown" : ip, k -> new RateBucket());
        synchronized (b) {
            if (now - b.windowStart > IP_RATE_WINDOW_MS) {
                b.windowStart = now;
                b.count = 0;
            }
            b.count++;
            if (b.count > IP_RATE_LIMIT) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded");
            }
        }
    }

    private String randomToken(int bytes) {
        byte[] buf = new byte[bytes];
        secureRandom.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private String randomAccessCode(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(CODE_ALPHABET.charAt(secureRandom.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    static String clientIp(HttpServletRequest request) {
        if (request == null) return null;
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }

    private static final class RateBucket {
        long windowStart = System.currentTimeMillis();
        int count;
    }
}
