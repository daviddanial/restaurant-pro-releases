package com.telerad.study;

import com.telerad.audit.AuditService;
import com.telerad.auth.UserAccount;
import com.telerad.auth.UserPrincipal;
import com.telerad.common.Role;
import com.telerad.common.StudyPriority;
import com.telerad.common.StudyStatus;
import com.telerad.report.ReportRepository;
import com.telerad.tenant.Tenant;
import com.telerad.tenant.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StudyServiceTest {
    @Mock StudyRepository studyRepository;
    @Mock SeriesQueryRepository seriesQueryRepository;
    @Mock InstanceRepository instanceRepository;
    @Mock PatientRepository patientRepository;
    @Mock TenantRepository tenantRepository;
    @Mock ReportRepository reportRepository;
    @Mock AuditService auditService;
    @Mock com.telerad.auth.UserAccountRepository userAccountRepository;
    @InjectMocks StudyService studyService;

    private UUID radId;
    private UUID tenantId;
    private Study readyStudy;

    @BeforeEach
    void setUp() {
        radId = UUID.randomUUID();
        tenantId = UUID.randomUUID();
        readyStudy = Study.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .studyInstanceUid("1.2.3")
                .status(StudyStatus.READY)
                .priority(StudyPriority.ROUTINE)
                .build();
        stubTenantAndReport();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(Role role, UUID userId, UUID userTenantId) {
        UserPrincipal p = new UserPrincipal(userId, "u@t.local", "User", role, userTenantId, "DEMO");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(p, null, p.authorities()));
    }

    private void stubTenantAndReport() {
        lenient().when(tenantRepository.findById(tenantId))
                .thenReturn(Optional.of(Tenant.builder().id(tenantId).code("DEMO").name("Demo").build()));
        lenient().when(reportRepository.findByStudyId(any())).thenReturn(Optional.empty());
        lenient().when(patientRepository.findById(any())).thenReturn(Optional.empty());
    }

    @Test
    void claim_radiologistClaimsReadyStudy() {
        authenticate(Role.RADIOLOGIST, radId, null);
        when(studyRepository.findById(readyStudy.getId())).thenReturn(Optional.of(readyStudy));
        when(studyRepository.save(readyStudy)).thenReturn(readyStudy);

        StudyService.StudyListItem item = studyService.claim(readyStudy.getId());

        assertThat(readyStudy.getStatus()).isEqualTo(StudyStatus.IN_PROGRESS);
        assertThat(readyStudy.getAssignedTo()).isEqualTo(radId);
        assertThat(readyStudy.getClaimedAt()).isNotNull();
        assertThat(item.status()).isEqualTo(StudyStatus.IN_PROGRESS);
        verify(auditService).record(eq(tenantId), eq(radId), eq("CLAIM"), eq("Study"),
                eq(readyStudy.getId().toString()), isNull(), isNull());
    }

    @Test
    void claim_hospitalUserForbidden() {
        authenticate(Role.HOSPITAL_USER, UUID.randomUUID(), tenantId);
        assertThatThrownBy(() -> studyService.claim(readyStudy.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void claim_notFound() {
        authenticate(Role.RADIOLOGIST, radId, null);
        when(studyRepository.findById(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> studyService.claim(UUID.randomUUID()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void claim_notClaimableWhenReported() {
        authenticate(Role.RADIOLOGIST, radId, null);
        readyStudy.setStatus(StudyStatus.REPORTED);
        when(studyRepository.findById(readyStudy.getId())).thenReturn(Optional.of(readyStudy));
        assertThatThrownBy(() -> studyService.claim(readyStudy.getId()))
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(rse.getReason()).contains("not claimable");
                });
    }

    @Test
    void claim_alreadyClaimedByAnother() {
        authenticate(Role.RADIOLOGIST, radId, null);
        readyStudy.setStatus(StudyStatus.IN_PROGRESS);
        readyStudy.setAssignedTo(UUID.randomUUID());
        when(studyRepository.findById(readyStudy.getId())).thenReturn(Optional.of(readyStudy));
        assertThatThrownBy(() -> studyService.claim(readyStudy.getId()))
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getReason()).contains("Already claimed"));
    }

    @Test
    void claim_sameRadiologistCanReclaimInProgress() {
        authenticate(Role.RADIOLOGIST, radId, null);
        readyStudy.setStatus(StudyStatus.IN_PROGRESS);
        readyStudy.setAssignedTo(radId);
        when(studyRepository.findById(readyStudy.getId())).thenReturn(Optional.of(readyStudy));
        when(studyRepository.save(readyStudy)).thenReturn(readyStudy);

        StudyService.StudyListItem item = studyService.claim(readyStudy.getId());
        assertThat(item.assignedTo()).isEqualTo(radId);
    }

    @Test
    void hospitalStudies_forbiddenForOtherHospital() {
        authenticate(Role.HOSPITAL_USER, UUID.randomUUID(), tenantId);
        UUID otherHospital = UUID.randomUUID();
        assertThatThrownBy(() -> studyService.hospitalStudies(otherHospital))
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void hospitalStudies_allowedForOwnHospital() {
        authenticate(Role.HOSPITAL_USER, UUID.randomUUID(), tenantId);
        when(studyRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)).thenReturn(List.of(readyStudy));

        List<StudyService.StudyListItem> items = studyService.hospitalStudies(tenantId);
        assertThat(items).hasSize(1);
        assertThat(items.get(0).id()).isEqualTo(readyStudy.getId());
    }

    @Test
    void hospitalStudies_adminCanAccessAny() {
        authenticate(Role.ADMIN, UUID.randomUUID(), null);
        when(studyRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)).thenReturn(List.of());
        assertThat(studyService.hospitalStudies(tenantId)).isEmpty();
    }

    @Test
    void getStudy_hospitalUserCannotViewOtherTenant() {
        authenticate(Role.HOSPITAL_USER, UUID.randomUUID(), UUID.randomUUID());
        when(studyRepository.findById(readyStudy.getId())).thenReturn(Optional.of(readyStudy));
        assertThatThrownBy(() -> studyService.getStudy(readyStudy.getId()))
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void worklist_radiologistUsesFindWorklist() {
        authenticate(Role.RADIOLOGIST, radId, null);
        when(studyRepository.findWorklist(eq(List.of(StudyStatus.READY, StudyStatus.IN_PROGRESS)), isNull(), isNull()))
                .thenReturn(List.of(readyStudy));
        List<StudyService.StudyListItem> items = studyService.worklist(null, null, null);
        assertThat(items).hasSize(1);
    }

    @Test
    void worklist_withStatusAndPriorityFilters() {
        authenticate(Role.ADMIN, UUID.randomUUID(), null);
        when(studyRepository.findWorklist(eq(List.of(StudyStatus.READY)), eq("CT"), eq(StudyPriority.STAT)))
                .thenReturn(List.of());
        assertThat(studyService.worklist("CT", "STAT", "READY")).isEmpty();
    }

    @Test
    void assign_hospitalStaffAssignsReadyStudy() {
        authenticate(Role.HOSPITAL_USER, UUID.randomUUID(), tenantId);
        UserAccount rad = UserAccount.builder()
                .id(radId).email("rad@t.local").fullName("Dr. Jane").role(Role.RADIOLOGIST)
                .passwordHash("x").active(true).build();
        when(studyRepository.findById(readyStudy.getId())).thenReturn(Optional.of(readyStudy));
        when(userAccountRepository.findById(radId)).thenReturn(Optional.of(rad));
        when(studyRepository.save(readyStudy)).thenReturn(readyStudy);

        StudyService.StudyListItem item = studyService.assign(readyStudy.getId(), radId);

        assertThat(readyStudy.getAssignedTo()).isEqualTo(radId);
        assertThat(readyStudy.getStatus()).isEqualTo(StudyStatus.IN_PROGRESS);
        assertThat(item.assignedTo()).isEqualTo(radId);
        verify(auditService).record(eq(tenantId), any(), eq("ASSIGN"), eq("Study"),
                eq(readyStudy.getId().toString()), eq("Dr. Jane"), isNull());
    }

    @Test
    void assign_forbiddenForOtherHospital() {
        authenticate(Role.HOSPITAL_USER, UUID.randomUUID(), UUID.randomUUID());
        when(studyRepository.findById(readyStudy.getId())).thenReturn(Optional.of(readyStudy));
        assertThatThrownBy(() -> studyService.assign(readyStudy.getId(), radId))
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void assign_rejectsNonRadiologist() {
        authenticate(Role.HOSPITAL_USER, UUID.randomUUID(), tenantId);
        UserAccount staff = UserAccount.builder()
                .id(radId).email("s@t.local").fullName("Staff").role(Role.HOSPITAL_USER)
                .passwordHash("x").active(true).build();
        when(studyRepository.findById(readyStudy.getId())).thenReturn(Optional.of(readyStudy));
        when(userAccountRepository.findById(radId)).thenReturn(Optional.of(staff));
        assertThatThrownBy(() -> studyService.assign(readyStudy.getId(), radId))
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getReason()).contains("not an active radiologist"));
    }

    @Test
    void priors_returnsOnlyStudiesOlderThanTheCurrentOne() {
        authenticate(Role.RADIOLOGIST, radId, null);
        UUID patientId = UUID.randomUUID();
        readyStudy.setPatientId(patientId);
        readyStudy.setModality("MR");
        readyStudy.setStudyDate(java.time.LocalDate.of(2026, 8, 5));

        Study older = priorCandidate(patientId, "MR", java.time.LocalDate.of(2026, 5, 7));
        Study newer = priorCandidate(patientId, "MR", java.time.LocalDate.of(2026, 9, 1));

        when(studyRepository.findById(readyStudy.getId())).thenReturn(Optional.of(readyStudy));
        when(studyRepository.findPriors(patientId, tenantId, readyStudy.getId(), "MR"))
                .thenReturn(List.of(newer, older));

        List<StudyService.StudyListItem> priors = studyService.priors(readyStudy.getId());

        assertThat(priors).extracting(StudyService.StudyListItem::id).containsExactly(older.getId());
    }

    @Test
    void priors_fallsBackToCreatedAtWhenStudyDatesMatch() {
        authenticate(Role.RADIOLOGIST, radId, null);
        UUID patientId = UUID.randomUUID();
        java.time.LocalDate sameDay = java.time.LocalDate.of(2026, 8, 5);
        readyStudy.setPatientId(patientId);
        readyStudy.setModality("CT");
        readyStudy.setStudyDate(sameDay);
        readyStudy.setCreatedAt(java.time.Instant.parse("2026-08-05T12:00:00Z"));

        Study earlierSameDay = priorCandidate(patientId, "CT", sameDay);
        earlierSameDay.setCreatedAt(java.time.Instant.parse("2026-08-05T08:00:00Z"));

        when(studyRepository.findById(readyStudy.getId())).thenReturn(Optional.of(readyStudy));
        when(studyRepository.findPriors(patientId, tenantId, readyStudy.getId(), "CT"))
                .thenReturn(List.of(earlierSameDay));

        assertThat(studyService.priors(readyStudy.getId())).hasSize(1);
    }

    @Test
    void priors_emptyWhenStudyHasNoPatient() {
        authenticate(Role.RADIOLOGIST, radId, null);
        when(studyRepository.findById(readyStudy.getId())).thenReturn(Optional.of(readyStudy));

        assertThat(studyService.priors(readyStudy.getId())).isEmpty();
        verify(studyRepository, never()).findPriors(any(), any(), any(), any());
    }

    @Test
    void priors_hospitalUserCannotReachAnotherTenant() {
        authenticate(Role.HOSPITAL_USER, UUID.randomUUID(), UUID.randomUUID());
        when(studyRepository.findById(readyStudy.getId())).thenReturn(Optional.of(readyStudy));
        assertThatThrownBy(() -> studyService.priors(readyStudy.getId()))
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    private Study priorCandidate(UUID patientId, String modality, java.time.LocalDate studyDate) {
        return Study.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .patientId(patientId)
                .studyInstanceUid("1.2.3." + UUID.randomUUID())
                .modality(modality)
                .studyDate(studyDate)
                .status(StudyStatus.DELIVERED)
                .priority(StudyPriority.ROUTINE)
                .createdAt(studyDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant())
                .build();
    }

    @Test
    void radiologists_listsActiveWithPendingCounts() {
        authenticate(Role.HOSPITAL_USER, UUID.randomUUID(), tenantId);
        UserAccount rad = UserAccount.builder()
                .id(radId).email("rad@t.local").fullName("Dr. Jane").role(Role.RADIOLOGIST)
                .passwordHash("x").active(true).build();
        when(userAccountRepository.findByRole(Role.RADIOLOGIST)).thenReturn(List.of(rad));
        when(studyRepository.countByAssignedToAndStatus(radId, StudyStatus.IN_PROGRESS)).thenReturn(2L);
        List<StudyService.RadiologistOption> opts = studyService.radiologists();
        assertThat(opts).hasSize(1);
        assertThat(opts.get(0).fullName()).isEqualTo("Dr. Jane");
        assertThat(opts.get(0).pendingCount()).isEqualTo(2);
    }
}
