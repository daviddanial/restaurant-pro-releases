package com.telerad.annotation;

import com.telerad.audit.AuditService;
import com.telerad.auth.UserAccount;
import com.telerad.auth.UserAccountRepository;
import com.telerad.auth.UserPrincipal;
import com.telerad.common.Role;
import com.telerad.storage.ObjectStorageService;
import com.telerad.study.Study;
import com.telerad.study.StudyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Measurements and key images saved with a study.
 * <ul>
 *   <li>View: anyone who may view the study (same rule as the study itself).</li>
 *   <li>Add: radiologists and admins.</li>
 *   <li>Change / delete: the author, or an admin. Others see them read-only.</li>
 * </ul>
 * Every add, change and delete is written to the audit log.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StudyAnnotationService {
    static final int MAX_DATA_CHARS = 64 * 1024;
    static final int MAX_SNAPSHOT_BYTES = 3 * 1024 * 1024;
    private static final Pattern TOOL_NAME = Pattern.compile("[A-Za-z0-9_]{1,64}");
    private static final Pattern DATA_URL = Pattern.compile("^data:image/(jpeg|png);base64,([A-Za-z0-9+/=\\r\\n]+)$");

    private final StudyMeasurementRepository measurementRepository;
    private final StudyKeyImageRepository keyImageRepository;
    private final StudyRepository studyRepository;
    private final UserAccountRepository userRepository;
    private final ObjectStorageService storage;
    private final AuditService auditService;

    public record MeasurementRequest(String seriesInstanceUid, String sopInstanceUid, Integer frameNumber,
                                     String toolName, String data, String summary, String location) {}

    public record MeasurementDto(UUID id, String seriesInstanceUid, String sopInstanceUid, int frameNumber,
                                 String toolName, String data, String summary, String location,
                                 UUID createdBy, String createdByName, boolean mine,
                                 Instant createdAt, Instant updatedAt) {}

    public record KeyImageRequest(String seriesInstanceUid, String sopInstanceUid, Integer frameNumber,
                                  Double windowCenter, Double windowWidth, String location, String snapshotDataUrl) {}

    public record KeyImageDto(UUID id, String seriesInstanceUid, String sopInstanceUid, int frameNumber,
                              Double windowCenter, Double windowWidth, String location,
                              UUID createdBy, String createdByName, boolean mine, Instant createdAt,
                              boolean hasSnapshot) {}

    /** Snapshot and caption for the signed PDF report. */
    public record ReportImage(String location, byte[] jpeg, String contentType) {}

    // ---------------------------------------------------------------- measurements

    @Transactional(readOnly = true)
    public List<MeasurementDto> listMeasurements(UUID studyId) {
        UserPrincipal user = UserPrincipal.current();
        requireViewableStudy(studyId, user);
        List<StudyMeasurement> rows = measurementRepository.findByStudyIdOrderByCreatedAtAsc(studyId);
        Map<UUID, String> names = names(rows.stream().map(StudyMeasurement::getCreatedBy).toList());
        return rows.stream().map(m -> toDto(m, user, names)).toList();
    }

    /** Create, or update the caller's own measurement with this id (the viewer's annotation UID). */
    @Transactional
    public MeasurementDto upsertMeasurement(UUID studyId, UUID measurementId, MeasurementRequest req) {
        UserPrincipal user = UserPrincipal.current();
        Study study = requireViewableStudy(studyId, user);
        requireEditorRole(user);
        validate(req);
        Instant now = Instant.now();
        Optional<StudyMeasurement> existing = measurementRepository.findById(measurementId);
        StudyMeasurement m;
        String action;
        if (existing.isPresent()) {
            m = existing.get();
            if (!m.getStudyId().equals(studyId)) throw new ResponseStatusException(HttpStatus.CONFLICT, "Measurement belongs to another study");
            requireOwnerOrAdmin(m.getCreatedBy(), user);
            action = "MEASUREMENT_UPDATE";
        } else {
            m = StudyMeasurement.builder()
                    .id(measurementId).studyId(studyId).tenantId(study.getTenantId())
                    .createdBy(user.getId()).createdAt(now)
                    .build();
            action = "MEASUREMENT_ADD";
        }
        m.setSeriesInstanceUid(req.seriesInstanceUid().trim());
        m.setSopInstanceUid(req.sopInstanceUid().trim());
        m.setFrameNumber(frame(req.frameNumber()));
        m.setToolName(req.toolName());
        m.setDataJson(req.data());
        m.setSummary(clip(req.summary(), 500));
        m.setLocation(clip(req.location(), 255));
        m.setUpdatedAt(now);
        measurementRepository.save(m);
        auditService.record(study.getTenantId(), user.getId(), action, "StudyMeasurement", measurementId.toString(),
                "study=" + studyId + (m.getSummary() != null ? " " + m.getSummary() : ""), null);
        return toDto(m, user, names(List.of(m.getCreatedBy())));
    }

    @Transactional
    public void deleteMeasurement(UUID studyId, UUID measurementId) {
        UserPrincipal user = UserPrincipal.current();
        Study study = requireViewableStudy(studyId, user);
        requireEditorRole(user);
        Optional<StudyMeasurement> existing = measurementRepository.findById(measurementId);
        if (existing.isEmpty()) return; // never saved (e.g. cancelled draw): nothing to do
        StudyMeasurement m = existing.get();
        if (!m.getStudyId().equals(studyId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        requireOwnerOrAdmin(m.getCreatedBy(), user);
        measurementRepository.delete(m);
        auditService.record(study.getTenantId(), user.getId(), "MEASUREMENT_DELETE", "StudyMeasurement",
                measurementId.toString(), "study=" + studyId + (m.getSummary() != null ? " " + m.getSummary() : ""), null);
    }

    // ---------------------------------------------------------------- key images

    @Transactional(readOnly = true)
    public List<KeyImageDto> listKeyImages(UUID studyId) {
        UserPrincipal user = UserPrincipal.current();
        requireViewableStudy(studyId, user);
        List<StudyKeyImage> rows = keyImageRepository.findByStudyIdOrderByCreatedAtAsc(studyId);
        Map<UUID, String> names = names(rows.stream().map(StudyKeyImage::getCreatedBy).toList());
        return rows.stream().map(k -> toDto(k, user, names)).toList();
    }

    /** Mark a slice as key image (or refresh its snapshot if it is already marked). */
    @Transactional
    public KeyImageDto addKeyImage(UUID studyId, KeyImageRequest req) {
        UserPrincipal user = UserPrincipal.current();
        Study study = requireViewableStudy(studyId, user);
        requireEditorRole(user);
        requireUid(req.seriesInstanceUid(), "seriesInstanceUid");
        requireUid(req.sopInstanceUid(), "sopInstanceUid");
        int frame = frame(req.frameNumber());
        Snapshot snap = req.snapshotDataUrl() == null || req.snapshotDataUrl().isBlank() ? null : decode(req.snapshotDataUrl());

        StudyKeyImage k = keyImageRepository
                .findByStudyIdAndSopInstanceUidAndFrameNumber(studyId, req.sopInstanceUid().trim(), frame)
                .orElse(null);
        if (k != null) {
            requireOwnerOrAdmin(k.getCreatedBy(), user);
        } else {
            k = StudyKeyImage.builder()
                    .id(UUID.randomUUID()).studyId(studyId).tenantId(study.getTenantId())
                    .seriesInstanceUid(req.seriesInstanceUid().trim()).sopInstanceUid(req.sopInstanceUid().trim())
                    .frameNumber(frame).createdBy(user.getId()).createdAt(Instant.now())
                    .build();
        }
        k.setWindowCenter(req.windowCenter());
        k.setWindowWidth(req.windowWidth());
        k.setLocation(clip(req.location(), 255));
        if (snap != null) {
            String key = study.getTenantId() + "/key-images/" + studyId + "/" + k.getId() + "." + snap.extension();
            storage.put(key, snap.bytes(), snap.contentType());
            k.setSnapshotStorageKey(key);
        }
        keyImageRepository.save(k);
        auditService.record(study.getTenantId(), user.getId(), "KEY_IMAGE_ADD", "StudyKeyImage", k.getId().toString(),
                "study=" + studyId + (k.getLocation() != null ? " " + k.getLocation() : ""), null);
        return toDto(k, user, names(List.of(k.getCreatedBy())));
    }

    @Transactional
    public void deleteKeyImage(UUID studyId, UUID keyImageId) {
        UserPrincipal user = UserPrincipal.current();
        Study study = requireViewableStudy(studyId, user);
        requireEditorRole(user);
        StudyKeyImage k = keyImageRepository.findById(keyImageId)
                .filter(x -> x.getStudyId().equals(studyId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        requireOwnerOrAdmin(k.getCreatedBy(), user);
        keyImageRepository.delete(k);
        if (k.getSnapshotStorageKey() != null) {
            try {
                storage.delete(k.getSnapshotStorageKey());
            } catch (Exception e) {
                log.warn("Could not delete key image snapshot {}: {}", k.getSnapshotStorageKey(), e.getMessage());
            }
        }
        auditService.record(study.getTenantId(), user.getId(), "KEY_IMAGE_DELETE", "StudyKeyImage", keyImageId.toString(),
                "study=" + studyId + (k.getLocation() != null ? " " + k.getLocation() : ""), null);
    }

    /** Snapshot bytes for display in the viewer / report editor. Returns [contentType, bytes]. */
    @Transactional(readOnly = true)
    public ReportImage keyImageSnapshot(UUID studyId, UUID keyImageId) {
        UserPrincipal user = UserPrincipal.current();
        requireViewableStudy(studyId, user);
        StudyKeyImage k = keyImageRepository.findById(keyImageId)
                .filter(x -> x.getStudyId().equals(studyId) && x.getSnapshotStorageKey() != null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return new ReportImage(k.getLocation(), storage.getBytes(k.getSnapshotStorageKey()), contentTypeOf(k.getSnapshotStorageKey()));
    }

    /** Key image snapshots for the signed PDF. Caller has already authorised access to the study. */
    @Transactional(readOnly = true)
    public List<ReportImage> reportImages(UUID studyId) {
        List<ReportImage> out = new ArrayList<>();
        for (StudyKeyImage k : keyImageRepository.findByStudyIdOrderByCreatedAtAsc(studyId)) {
            if (k.getSnapshotStorageKey() == null) continue;
            try {
                out.add(new ReportImage(k.getLocation(), storage.getBytes(k.getSnapshotStorageKey()),
                        contentTypeOf(k.getSnapshotStorageKey())));
            } catch (Exception e) {
                log.warn("Key image {} snapshot missing: {}", k.getId(), e.getMessage());
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- helpers

    private Study requireViewableStudy(UUID studyId, UserPrincipal user) {
        Study study = studyRepository.findById(studyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Study not found"));
        if (user.getRole() == Role.ADMIN || user.getRole() == Role.RADIOLOGIST) return study;
        if (user.getRole() == Role.HOSPITAL_USER && study.getTenantId().equals(user.getTenantId())) return study;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    private static void requireEditorRole(UserPrincipal user) {
        if (user.getRole() != Role.RADIOLOGIST && user.getRole() != Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only radiologists can add measurements");
        }
    }

    private static void requireOwnerOrAdmin(UUID ownerId, UserPrincipal user) {
        if (user.getRole() != Role.ADMIN && !ownerId.equals(user.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Created by another user (read-only)");
        }
    }

    private static void validate(MeasurementRequest req) {
        if (req == null) throw bad("Missing body");
        requireUid(req.seriesInstanceUid(), "seriesInstanceUid");
        requireUid(req.sopInstanceUid(), "sopInstanceUid");
        if (req.toolName() == null || !TOOL_NAME.matcher(req.toolName()).matches()) throw bad("Invalid toolName");
        if (req.data() == null || req.data().isBlank()) throw bad("Missing data");
        if (req.data().length() > MAX_DATA_CHARS) throw bad("Measurement data too large");
    }

    private static void requireUid(String uid, String field) {
        if (uid == null || uid.isBlank() || uid.trim().length() > 128) throw bad("Invalid " + field);
    }

    private static int frame(Integer f) {
        if (f == null) return 1;
        if (f < 1 || f > 100_000) throw bad("Invalid frameNumber");
        return f;
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() <= max ? t : t.substring(0, max);
    }

    record Snapshot(byte[] bytes, String contentType, String extension) {}

    static Snapshot decode(String dataUrl) {
        Matcher m = DATA_URL.matcher(dataUrl.trim());
        if (!m.matches()) throw bad("Snapshot must be a JPEG or PNG data URL");
        byte[] bytes;
        try {
            bytes = Base64.getMimeDecoder().decode(m.group(2));
        } catch (IllegalArgumentException e) {
            throw bad("Snapshot is not valid base64");
        }
        if (bytes.length == 0 || bytes.length > MAX_SNAPSHOT_BYTES) throw bad("Snapshot too large");
        boolean jpeg = m.group(1).equals("jpeg");
        // Check the magic bytes match the declared type.
        boolean ok = jpeg
                ? bytes.length > 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8
                : bytes.length > 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
        if (!ok) throw bad("Snapshot content does not match its type");
        return new Snapshot(bytes, jpeg ? "image/jpeg" : "image/png", jpeg ? "jpg" : "png");
    }

    private static String contentTypeOf(String key) {
        return key.endsWith(".png") ? "image/png" : "image/jpeg";
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private Map<UUID, String> names(Collection<UUID> ids) {
        Map<UUID, String> out = new HashMap<>();
        for (UserAccount u : userRepository.findAllById(new HashSet<>(ids))) out.put(u.getId(), u.getFullName());
        return out;
    }

    private static MeasurementDto toDto(StudyMeasurement m, UserPrincipal user, Map<UUID, String> names) {
        return new MeasurementDto(m.getId(), m.getSeriesInstanceUid(), m.getSopInstanceUid(), m.getFrameNumber(),
                m.getToolName(), m.getDataJson(), m.getSummary(), m.getLocation(), m.getCreatedBy(),
                names.get(m.getCreatedBy()), m.getCreatedBy().equals(user.getId()), m.getCreatedAt(), m.getUpdatedAt());
    }

    private static KeyImageDto toDto(StudyKeyImage k, UserPrincipal user, Map<UUID, String> names) {
        return new KeyImageDto(k.getId(), k.getSeriesInstanceUid(), k.getSopInstanceUid(), k.getFrameNumber(),
                k.getWindowCenter(), k.getWindowWidth(), k.getLocation(), k.getCreatedBy(), names.get(k.getCreatedBy()),
                k.getCreatedBy().equals(user.getId()), k.getCreatedAt(), k.getSnapshotStorageKey() != null);
    }
}
