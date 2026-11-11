package com.telerad.report;

import com.telerad.audit.AuditService;
import com.telerad.auth.UserPrincipal;
import com.telerad.common.ReportStatus;
import com.telerad.common.Role;
import com.telerad.common.StudyStatus;
import com.telerad.notify.NotificationService;
import com.telerad.storage.ObjectStorageService;
import com.telerad.study.PatientRepository;
import com.telerad.study.Study;
import com.telerad.study.StudyRepository;
import com.telerad.tenant.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {
    @Mock ReportRepository reportRepository;
    @Mock StudyRepository studyRepository;
    @Mock PatientRepository patientRepository;
    @Mock TenantRepository tenantRepository;
    @Mock ObjectStorageService storage;
    @Mock AuditService auditService;
    @Mock ReportDeliveryService reportDeliveryService;
    @Mock NotificationService notificationService;
    @Mock com.telerad.annotation.StudyAnnotationService annotationService;
    @InjectMocks ReportService reportService;

    private UUID radId;
    private UUID studyId;
    private UUID tenantId;
    private Study study;

    @BeforeEach
    void setUp() {
        radId = UUID.randomUUID();
        studyId = UUID.randomUUID();
        tenantId = UUID.randomUUID();
        study = Study.builder()
                .id(studyId)
                .tenantId(tenantId)
                .studyInstanceUid("1.2.3.4")
                .status(StudyStatus.IN_PROGRESS)
                .assignedTo(radId)
                .accessionNumber("ACC1")
                .modality("CT")
                .build();
        authenticate(Role.RADIOLOGIST, radId);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(Role role, UUID userId) {
        UserPrincipal p = new UserPrincipal(userId, "rad@telerad.local", "Dr Rad", role, null, null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(p, null, p.authorities()));
    }

    @Test
    void saveDraft_createsNewReport() {
        when(studyRepository.findById(studyId)).thenReturn(Optional.of(study));
        when(reportRepository.findByStudyId(studyId)).thenReturn(Optional.empty());
        when(reportRepository.save(any(Report.class))).thenAnswer(inv -> {
            Report r = inv.getArgument(0);
            if (r.getId() == null) r.setId(UUID.randomUUID());
            return r;
        });

        ReportService.ReportDto dto = reportService.saveDraft(studyId,
                new ReportService.ReportRequest("findings", "impression", true));

        assertThat(dto.findings()).isEqualTo("findings");
        assertThat(dto.impression()).isEqualTo("impression");
        assertThat(dto.critical()).isTrue();
        assertThat(dto.status()).isEqualTo(ReportStatus.DRAFT);
        verify(reportRepository).save(any(Report.class));
    }

    @Test
    void saveDraft_movesReadyStudyToInProgress() {
        study.setStatus(StudyStatus.READY);
        study.setAssignedTo(null);
        when(studyRepository.findById(studyId)).thenReturn(Optional.of(study));
        when(reportRepository.findByStudyId(studyId)).thenReturn(Optional.empty());
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(studyRepository.save(study)).thenReturn(study);

        reportService.saveDraft(studyId, new ReportService.ReportRequest("f", "i", false));

        assertThat(study.getStatus()).isEqualTo(StudyStatus.IN_PROGRESS);
        assertThat(study.getAssignedTo()).isEqualTo(radId);
    }

    @Test
    void saveDraft_rejectsAlreadySigned() {
        Report signed = Report.builder().id(UUID.randomUUID()).studyId(studyId)
                .status(ReportStatus.SIGNED).build();
        when(studyRepository.findById(studyId)).thenReturn(Optional.of(study));
        when(reportRepository.findByStudyId(studyId)).thenReturn(Optional.of(signed));

        assertThatThrownBy(() -> reportService.saveDraft(studyId,
                new ReportService.ReportRequest("f", "i", false)))
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void saveDraft_forbiddenForHospitalUser() {
        authenticate(Role.HOSPITAL_USER, UUID.randomUUID());
        assertThatThrownBy(() -> reportService.saveDraft(studyId,
                new ReportService.ReportRequest("f", "i", false)))
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void saveDraft_conflictWhenClaimedByAnother() {
        study.setAssignedTo(UUID.randomUUID());
        when(studyRepository.findById(studyId)).thenReturn(Optional.of(study));
        assertThatThrownBy(() -> reportService.saveDraft(studyId,
                new ReportService.ReportRequest("f", "i", false)))
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(rse.getReason()).contains("claimed by another");
                });
    }

    @Test
    void sign_requiresDraftWithImpression() {
        when(studyRepository.findById(studyId)).thenReturn(Optional.of(study));
        when(reportRepository.findByStudyId(studyId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> reportService.sign(studyId))
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getReason()).contains("No draft"));
    }

    @Test
    void sign_requiresNonBlankImpression() {
        Report draft = Report.builder().id(UUID.randomUUID()).studyId(studyId)
                .status(ReportStatus.DRAFT).findings("f").impression("  ").build();
        when(studyRepository.findById(studyId)).thenReturn(Optional.of(study));
        when(reportRepository.findByStudyId(studyId)).thenReturn(Optional.of(draft));
        assertThatThrownBy(() -> reportService.sign(studyId))
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getReason()).contains("Impression required"));
    }

    @Test
    void sign_rejectsAlreadySigned() {
        Report signed = Report.builder().id(UUID.randomUUID()).studyId(studyId)
                .status(ReportStatus.SIGNED).impression("ok").build();
        when(studyRepository.findById(studyId)).thenReturn(Optional.of(study));
        when(reportRepository.findByStudyId(studyId)).thenReturn(Optional.of(signed));
        assertThatThrownBy(() -> reportService.sign(studyId))
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getReason()).contains("Already signed"));
    }

    @Test
    void sign_success_storesPdfAndDelivers() {
        UUID reportId = UUID.randomUUID();
        Report draft = Report.builder().id(reportId).studyId(studyId)
                .status(ReportStatus.DRAFT).findings("Normal").impression("No acute findings").build();
        when(studyRepository.findById(studyId)).thenReturn(Optional.of(study));
        when(reportRepository.findByStudyId(studyId)).thenReturn(Optional.of(draft));
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(studyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReportService.ReportDto dto = reportService.sign(studyId);

        assertThat(dto.status()).isEqualTo(ReportStatus.SIGNED);
        assertThat(dto.hasPdf()).isTrue();
        assertThat(study.getStatus()).isEqualTo(StudyStatus.DELIVERED);
        assertThat(study.getReportedAt()).isNotNull();
        assertThat(study.getDeliveredAt()).isNotNull();

        ArgumentCaptor<byte[]> pdfCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(storage, atLeastOnce()).put(contains("/reports/"), pdfCaptor.capture(), anyString());
        assertThat(pdfCaptor.getAllValues().get(0)).isNotEmpty();
        verify(reportDeliveryService).deliverPortal(eq(draft), anyString());
        verify(reportDeliveryService).deliverFhirStored(eq(draft), anyString());
        verify(reportDeliveryService).deliverFhirWebhook(eq(study), eq(draft), anyString());
        verify(reportDeliveryService).deliverEmailStub(eq(study), eq(draft), anyString());
        verify(notificationService).notifyReportSigned(eq(study), eq("No acute findings"), eq(false), anyString(), anyString());
        verify(auditService).record(eq(tenantId), eq(radId), eq("SIGN_REPORT"), eq("Report"),
                eq(reportId.toString()), anyString(), isNull());
    }

    @Test
    void getByStudy_notFound() {
        when(studyRepository.findById(studyId)).thenReturn(Optional.of(study));
        when(reportRepository.findByStudyId(studyId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> reportService.getByStudy(studyId))
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void hospitalReports_forbiddenForOtherHospital() {
        authenticate(Role.HOSPITAL_USER, UUID.randomUUID());
        // hospital user with different tenant
        UserPrincipal p = new UserPrincipal(UUID.randomUUID(), "s@h.local", "S", Role.HOSPITAL_USER,
                UUID.randomUUID(), "OTHER");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(p, null, p.authorities()));
        assertThatThrownBy(() -> reportService.hospitalReports(tenantId))
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void pdfBytes_missingKey() {
        Report report = Report.builder().id(UUID.randomUUID()).studyId(studyId)
                .status(ReportStatus.SIGNED).build();
        when(reportRepository.findById(report.getId())).thenReturn(Optional.of(report));
        when(studyRepository.findById(studyId)).thenReturn(Optional.of(study));
        assertThatThrownBy(() -> reportService.pdfBytes(report.getId()))
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getReason()).contains("PDF not available"));
    }
}
