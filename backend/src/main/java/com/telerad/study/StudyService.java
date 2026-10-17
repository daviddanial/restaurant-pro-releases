package com.telerad.study;

import com.telerad.audit.AuditService;
import com.telerad.auth.UserAccount;
import com.telerad.auth.UserAccountRepository;
import com.telerad.auth.UserPrincipal;
import com.telerad.common.Role;
import com.telerad.common.StudyStatus;
import com.telerad.report.Report;
import com.telerad.report.ReportRepository;
import com.telerad.tenant.Tenant;
import com.telerad.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class StudyService {
    private static final int MAX_PRIORS = 10;

    private final StudyRepository studyRepository;
    private final SeriesQueryRepository seriesQueryRepository;
    private final InstanceRepository instanceRepository;
    private final PatientRepository patientRepository;
    private final TenantRepository tenantRepository;
    private final ReportRepository reportRepository;
    private final AuditService auditService;
    private final UserAccountRepository userAccountRepository;

    @Transactional(readOnly = true)
    public List<StudyListItem> worklist(String modality, String priority, String status) {
        UserPrincipal user = UserPrincipal.current();
        List<StudyStatus> statuses;
        if (status != null && !status.isBlank()) {
            statuses = List.of(StudyStatus.valueOf(status));
        } else {
            statuses = List.of(StudyStatus.READY, StudyStatus.IN_PROGRESS);
        }

        List<Study> studies;
        if (user.getRole() == Role.RADIOLOGIST || user.getRole() == Role.ADMIN) {
            com.telerad.common.StudyPriority pri = null;
            if (priority != null && !priority.isBlank()) {
                pri = com.telerad.common.StudyPriority.valueOf(priority);
            }
            studies = studyRepository.findWorklist(statuses, emptyToNull(modality), pri);
        } else if (user.getRole() == Role.HOSPITAL_USER) {
            studies = studyRepository.findByTenantIdOrderByCreatedAtDesc(user.getTenantId());
        } else {
            studies = List.of();
        }
        return studies.stream().map(this::toListItem).toList();
    }

    @Transactional(readOnly = true)
    public List<StudyListItem> hospitalStudies(UUID hospitalId) {
        UserPrincipal user = UserPrincipal.current();
        assertHospitalAccess(user, hospitalId);
        return studyRepository.findByTenantIdOrderByCreatedAtDesc(hospitalId).stream()
                .map(this::toListItem).toList();
    }

    @Transactional(readOnly = true)
    public StudyDetail getStudy(UUID id) {
        Study study = studyRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Study not found"));
        assertCanView(UserPrincipal.current(), study);
        return toDetail(study);
    }

    /**
     * Earlier studies for the same patient, for side-by-side comparison. Body part is not
     * modelled, so same-modality studies are ranked first as the closest available proxy.
     */
    @Transactional(readOnly = true)
    public List<StudyListItem> priors(UUID id) {
        Study study = studyRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Study not found"));
        assertCanView(UserPrincipal.current(), study);
        return findPriorStudiesForSnapshot(study).stream().map(this::toListItem).toList();
    }

    /**
     * Prior studies eligible to freeze into a patient image share (same ranking as viewer compare).
     * Caller must authorize; does not check UserPrincipal.
     */
    @Transactional(readOnly = true)
    public List<Study> findPriorStudiesForSnapshot(Study study) {
        if (study.getPatientId() == null) {
            return List.of();
        }
        return studyRepository
                .findPriors(study.getPatientId(), study.getTenantId(), study.getId(), study.getModality())
                .stream()
                .filter(candidate -> isPriorOf(candidate, study))
                .filter(candidate -> candidate.getStatus() != StudyStatus.RECEIVED)
                .limit(MAX_PRIORS)
                .toList();
    }

    /** Study detail without role checks — caller must authorize (e.g. share snapshot). */
    @Transactional(readOnly = true)
    public StudyDetail detailWithoutAuth(UUID id) {
        Study study = studyRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Study not found"));
        return toDetail(study);
    }

    @Transactional(readOnly = true)
    public StudyListItem listItemWithoutAuth(UUID id) {
        Study study = studyRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Study not found"));
        return toListItem(study);
    }

    private boolean isPriorOf(Study candidate, Study current) {
        java.time.LocalDate candidateDate = candidate.getStudyDate();
        java.time.LocalDate currentDate = current.getStudyDate();
        if (candidateDate != null && currentDate != null && !candidateDate.isEqual(currentDate)) {
            return candidateDate.isBefore(currentDate);
        }
        return candidate.getCreatedAt().isBefore(current.getCreatedAt());
    }

    @Transactional
    public StudyListItem claim(UUID id) {
        UserPrincipal user = UserPrincipal.current();
        if (user.getRole() != Role.RADIOLOGIST && user.getRole() != Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only radiologists can claim");
        }
        Study study = studyRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (study.getStatus() != StudyStatus.READY && study.getStatus() != StudyStatus.IN_PROGRESS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Study not claimable");
        }
        if (study.getAssignedTo() != null && !study.getAssignedTo().equals(user.getId())
                && study.getStatus() == StudyStatus.IN_PROGRESS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already claimed by another radiologist");
        }
        study.setAssignedTo(user.getId());
        study.setClaimedAt(Instant.now());
        study.setStatus(StudyStatus.IN_PROGRESS);
        studyRepository.save(study);
        auditService.record(study.getTenantId(), user.getId(), "CLAIM", "Study", id.toString(), null, null);
        return toListItem(study);
    }

    @Transactional(readOnly = true)
    public List<RadiologistOption> radiologists() {
        UserPrincipal user = UserPrincipal.current();
        if (user.getRole() != Role.HOSPITAL_USER && user.getRole() != Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed");
        }
        return userAccountRepository.findByRole(Role.RADIOLOGIST).stream()
                .filter(UserAccount::isActive)
                .map(u -> new RadiologistOption(
                        u.getId(),
                        u.getFullName(),
                        (int) studyRepository.countByAssignedToAndStatus(u.getId(), StudyStatus.IN_PROGRESS)))
                .toList();
    }

    @Transactional
    public StudyListItem assign(UUID studyId, UUID radiologistId) {
        UserPrincipal user = UserPrincipal.current();
        if (user.getRole() != Role.HOSPITAL_USER && user.getRole() != Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only hospital staff can assign");
        }
        Study study = studyRepository.findById(studyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        assertHospitalAccess(user, study.getTenantId());
        if (study.getStatus() != StudyStatus.READY && study.getStatus() != StudyStatus.IN_PROGRESS) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Study is not assignable");
        }
        UserAccount rad = userAccountRepository.findById(radiologistId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown radiologist"));
        if (rad.getRole() != Role.RADIOLOGIST || !rad.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User is not an active radiologist");
        }
        study.setAssignedTo(rad.getId());
        study.setClaimedAt(Instant.now());
        study.setStatus(StudyStatus.IN_PROGRESS);
        studyRepository.save(study);
        auditService.record(study.getTenantId(), user.getId(), "ASSIGN", "Study", studyId.toString(),
                rad.getFullName(), null);
        return toListItem(study);
    }

    private void assertHospitalAccess(UserPrincipal user, UUID hospitalId) {
        if (user.getRole() == Role.ADMIN) return;
        if (user.getRole() == Role.HOSPITAL_USER && hospitalId.equals(user.getTenantId())) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    private void assertCanView(UserPrincipal user, Study study) {
        if (user.getRole() == Role.ADMIN || user.getRole() == Role.RADIOLOGIST) return;
        if (user.getRole() == Role.HOSPITAL_USER && study.getTenantId().equals(user.getTenantId())) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    private StudyListItem toListItem(Study s) {
        String patientName = null;
        String patientMrn = null;
        String patientSex = null;
        java.time.LocalDate patientBirthDate = null;
        if (s.getPatientId() != null) {
            Optional<Patient> p = patientRepository.findById(s.getPatientId());
            if (p.isPresent()) {
                patientName = p.get().getPatientName();
                patientMrn = p.get().getPatientId();
                patientSex = p.get().getSex();
                patientBirthDate = p.get().getBirthDate();
            }
        }
        String hospitalName = tenantRepository.findById(s.getTenantId()).map(Tenant::getName).orElse(null);
        boolean hasSigned = reportRepository.findByStudyId(s.getId())
                .map(r -> r.getStatus() == com.telerad.common.ReportStatus.SIGNED).orElse(false);
        boolean overdue = s.getSlaDueAt() != null
                && Instant.now().isAfter(s.getSlaDueAt())
                && s.getStatus() != StudyStatus.REPORTED
                && s.getStatus() != StudyStatus.DELIVERED;
        return new StudyListItem(
                s.getId(), s.getTenantId(), hospitalName, s.getStudyInstanceUid(), s.getAccessionNumber(),
                s.getStudyDescription(), s.getModality(), s.getStudyDate(), s.getPriority(), s.getStatus(),
                s.getAssignedTo(), s.getInstanceCount(), s.getSource(), patientName, patientMrn,
                patientSex, patientBirthDate,
                s.getReadyAt(), s.getReportedAt(), hasSigned,
                s.getSlaDueAt(), s.getSlaBreachedAt(), overdue
        );
    }

    private StudyDetail toDetail(Study s) {
        StudyListItem base = toListItem(s);
        List<SeriesDto> seriesDtos = new ArrayList<>();
        for (Series ser : seriesQueryRepository.findByStudyIdOrdered(s.getId())) {
            List<InstanceDto> instances = instanceRepository.findBySeriesIdOrderByInstanceNumberAsc(ser.getId())
                    .stream()
                    .map(i -> new InstanceDto(i.getId(), i.getSopInstanceUid(), i.getInstanceNumber(),
                            i.getRows(), i.getColumns(), i.getNumberOfFrames(),
                            i.getCineRate(), i.getFrameTime(),
                            parseDoubles(i.getImagePositionPatient()),
                            parseDoubles(i.getImageOrientationPatient()),
                            i.getTransferSyntaxUid()))
                    .toList();
            seriesDtos.add(new SeriesDto(ser.getId(), ser.getSeriesInstanceUid(), ser.getModality(),
                    ser.getSeriesNumber(), ser.getSeriesDescription(), ser.getInstanceCount(), instances));
        }
        Report report = reportRepository.findByStudyId(s.getId()).orElse(null);
        ReportSummary reportSummary = report == null ? null : new ReportSummary(
                report.getId(), report.getStatus(), report.getFindings(), report.getImpression(),
                report.isCritical(), report.getSignedAt(), report.getPdfStorageKey() != null);
        return new StudyDetail(base, seriesDtos, reportSummary);
    }

    private String emptyToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    /** Parse "x,y,z" (or longer) into a double array; null if blank/invalid. */
    private static double[] parseDoubles(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String[] parts = raw.split(",");
        double[] out = new double[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                out[i] = Double.parseDouble(parts[i].trim());
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    public record RadiologistOption(UUID id, String fullName, int pendingCount) {}

    public record StudyListItem(
            UUID id, UUID tenantId, String hospitalName, String studyInstanceUid, String accessionNumber,
            String studyDescription, String modality, java.time.LocalDate studyDate,
            com.telerad.common.StudyPriority priority, StudyStatus status, UUID assignedTo,
            int instanceCount, com.telerad.common.StudySource source, String patientName, String patientMrn,
            String patientSex, java.time.LocalDate patientBirthDate,
            Instant readyAt, Instant reportedAt, boolean hasSignedReport,
            Instant slaDueAt, Instant slaBreachedAt, boolean slaOverdue
    ) {}

    public record SeriesDto(UUID id, String seriesInstanceUid, String modality, Integer seriesNumber,
                            String seriesDescription, int instanceCount, List<InstanceDto> instances) {}

    public record InstanceDto(UUID id, String sopInstanceUid, Integer instanceNumber,
                              Integer rows, Integer columns, int numberOfFrames,
                              Double cineRate, Double frameTime,
                              double[] imagePositionPatient, double[] imageOrientationPatient,
                              String transferSyntaxUid) {}

    public record ReportSummary(UUID id, com.telerad.common.ReportStatus status, String findings,
                                String impression, boolean critical, Instant signedAt, boolean hasPdf) {}

    public record StudyDetail(StudyListItem study, List<SeriesDto> series, ReportSummary report) {}
}
