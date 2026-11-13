package com.telerad.images;

import com.telerad.audit.AuditService;
import com.telerad.auth.UserPrincipal;
import com.telerad.common.CloudStatus;
import com.telerad.common.Role;
import com.telerad.common.StudyStatus;
import com.telerad.common.UploadPolicy;
import com.telerad.gateway.Gateway;
import com.telerad.gateway.GatewayRepository;
import com.telerad.storage.ObjectStorageService;
import com.telerad.study.Instance;
import com.telerad.study.InstanceRepository;
import com.telerad.study.Study;
import com.telerad.study.StudyRepository;
import com.telerad.tenant.Tenant;
import com.telerad.tenant.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImageLocationServiceTest {
    @Mock StudyRepository studyRepository;
    @Mock InstanceRepository instanceRepository;
    @Mock TenantRepository tenantRepository;
    @Mock GatewayRepository gatewayRepository;
    @Mock ObjectStorageService storage;
    @Mock AuditService auditService;

    ImageLocationService service;
    final UUID tenantId = UUID.randomUUID();
    final UUID patientId = UUID.randomUUID();
    Tenant tenant;
    Gateway gateway;

    @BeforeEach
    void setUp() {
        service = new ImageLocationService(studyRepository, instanceRepository, tenantRepository,
                gatewayRepository, storage, auditService, "Africa/Addis_Ababa");
        tenant = Tenant.builder().id(tenantId).code("H").name("Hospital").active(true)
                .uploadPolicy(UploadPolicy.ON_DEMAND).priorsToSend(1).build();
        gateway = Gateway.builder().id(UUID.randomUUID()).tenantId(tenantId).name("gw").active(true)
                .lanUrl("https://h.edge.example").build();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(tenantRepository.findAll()).thenReturn(List.of(tenant));
        when(gatewayRepository.findById(gateway.getId())).thenReturn(Optional.of(gateway));
        when(studyRepository.save(any(Study.class))).thenAnswer(i -> i.getArgument(0));
        when(studyRepository.findPriors(any(), any(), any(), any())).thenReturn(List.of());
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private Study study(String uid, CloudStatus status) {
        Study s = Study.builder().id(UUID.randomUUID()).tenantId(tenantId).patientId(patientId)
                .studyInstanceUid(uid).modality("CT").status(StudyStatus.READY).cloudStatus(status)
                .instanceCount(3).studyDate(LocalDate.of(2026, 11, 13)).createdAt(Instant.now()).build();
        when(studyRepository.findByTenantIdAndStudyInstanceUid(tenantId, uid)).thenReturn(Optional.of(s));
        when(studyRepository.findById(s.getId())).thenReturn(Optional.of(s));
        return s;
    }

    private void login(Role role, UUID tenant) {
        UserPrincipal p = new UserPrincipal(UUID.randomUUID(), "u@x", "User", role, tenant, null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(p, null, p.authorities()));
    }

    @Test
    void indexedStudyStaysOnTheHospitalServer() {
        Study s = study("1.1", CloudStatus.AVAILABLE); // a new row starts as AVAILABLE
        when(instanceRepository.countWithoutCloudCopy(s.getId())).thenReturn(3L);
        when(instanceRepository.countWithCloudCopy(s.getId())).thenReturn(0L);

        service.afterIndexed(gateway, tenantId, "1.1", true);

        assertThat(s.getCloudStatus()).isEqualTo(CloudStatus.EDGE_ONLY);
        assertThat(s.getEdgeGatewayId()).isEqualTo(gateway.getId());
        assertThat(s.getCloudRequestedAt()).isNull();
    }

    @Test
    void matchingRuleRequestsTheStudyAndItsPrior() {
        tenant.setAutoSendRules("[{\"modality\":\"CT\"}]");
        Study s = study("1.1", CloudStatus.AVAILABLE);
        Study prior = study("0.9", CloudStatus.EDGE_ONLY);
        prior.setStudyDate(LocalDate.of(2026, 9, 1));
        prior.setEdgeGatewayId(gateway.getId());
        when(studyRepository.findPriors(eq(patientId), eq(tenantId), eq(s.getId()), any())).thenReturn(List.of(prior));
        when(instanceRepository.countWithoutCloudCopy(s.getId())).thenReturn(3L);

        service.afterIndexed(gateway, tenantId, "1.1", true);

        assertThat(s.getCloudStatus()).isEqualTo(CloudStatus.REQUESTED);
        assertThat(prior.getCloudStatus()).isEqualTo(CloudStatus.REQUESTED);
        assertThat(service.work(gateway)).isNotNull();
    }

    @Test
    void rulesWaitForTheLastBatch() {
        tenant.setAutoSendRules("[{\"modality\":\"CT\"}]");
        Study s = study("1.1", CloudStatus.AVAILABLE);
        when(instanceRepository.countWithoutCloudCopy(s.getId())).thenReturn(3L);
        service.afterIndexed(gateway, tenantId, "1.1", false);
        assertThat(s.getCloudStatus()).isEqualTo(CloudStatus.EDGE_ONLY);
    }

    @Test
    void uploadAllPolicyRequestsEveryIndexedStudy() {
        tenant.setUploadPolicy(UploadPolicy.ALL);
        Study s = study("1.1", CloudStatus.AVAILABLE);
        when(instanceRepository.countWithoutCloudCopy(s.getId())).thenReturn(3L);
        service.afterIndexed(gateway, tenantId, "1.1", true);
        assertThat(s.getCloudStatus()).isEqualTo(CloudStatus.REQUESTED);
    }

    @Test
    void newImagesInACloudStudyFollowIt() {
        Study s = study("1.1", CloudStatus.AVAILABLE);
        when(instanceRepository.countWithoutCloudCopy(s.getId())).thenReturn(1L);
        when(instanceRepository.countWithCloudCopy(s.getId())).thenReturn(2L);
        service.afterIndexed(gateway, tenantId, "1.1", true);
        assertThat(s.getCloudStatus()).isEqualTo(CloudStatus.REQUESTED);
    }

    @Test
    void studyIsAvailableOnceEveryFileIsUploaded() {
        Study s = study("1.1", CloudStatus.REQUESTED);
        s.setEdgeGatewayId(gateway.getId());
        when(instanceRepository.countWithoutCloudCopy(s.getId())).thenReturn(1L);
        service.afterUploaded(gateway, tenantId, "1.1");
        assertThat(s.getCloudStatus()).isEqualTo(CloudStatus.REQUESTED);

        when(instanceRepository.countWithoutCloudCopy(s.getId())).thenReturn(0L);
        service.afterUploaded(gateway, tenantId, "1.1");
        assertThat(s.getCloudStatus()).isEqualTo(CloudStatus.AVAILABLE);
        assertThat(s.getCloudAvailableAt()).isNotNull();
    }

    @Test
    void hospitalStaffSendTheirOwnStudiesOnly() {
        Study s = study("1.1", CloudStatus.EDGE_ONLY);
        s.setEdgeGatewayId(gateway.getId());

        login(Role.HOSPITAL_USER, UUID.randomUUID());
        assertThatThrownBy(() -> service.requestCloudCopy(s.getId()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));

        login(Role.HOSPITAL_USER, tenantId);
        var access = service.requestCloudCopy(s.getId());
        assertThat(access.cloudStatus()).isEqualTo(CloudStatus.REQUESTED);
        assertThat(access.lanUrl()).isEqualTo("https://h.edge.example");
        verify(auditService).record(eq(tenantId), any(), eq("CLOUD_COPY_REQUEST"), any(), any(), any(), any());
    }

    @Test
    void cannotRequestAStudyTheHospitalServerNoLongerHolds() {
        Study s = study("1.1", CloudStatus.EDGE_ONLY);
        s.setEdgeGatewayId(gateway.getId());
        s.setEdgeEvictedAt(Instant.now());
        login(Role.RADIOLOGIST, null);
        assertThatThrownBy(() -> service.requestCloudCopy(s.getId()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void retentionMarksThenDeletesOnlyAfterTheHospitalServerConfirms() {
        tenant.setCloudRetentionDays(4);
        Study s = study("1.1", CloudStatus.AVAILABLE);
        s.setEdgeGatewayId(gateway.getId());
        s.setStatus(StudyStatus.REPORTED);
        s.setReportedAt(Instant.now().minus(Duration.ofDays(5)));
        when(studyRepository.findCloudRetentionCandidates(eq(tenantId), any())).thenReturn(List.of(s));

        assertThat(service.markExpiredCloudCopies()).isEqualTo(1);
        assertThat(s.getCloudStatus()).isEqualTo(CloudStatus.PENDING_DELETE);
        verifyNoInteractions(storage);

        Instance inst = Instance.builder().id(UUID.randomUUID()).sopInstanceUid("1.1.1")
                .storageKey("t/1.1/s/1.1.1.dcm").gzipStorageKey("t/1.1/s/1.1.1.dcm.gz").encodingsChecked(true).build();
        when(instanceRepository.findByStudyId(s.getId())).thenReturn(List.of(inst));

        service.confirmHeld(gateway, "1.1", 3);

        assertThat(s.getCloudStatus()).isEqualTo(CloudStatus.EDGE_ONLY);
        assertThat(s.getCloudDeletedAt()).isNotNull();
        assertThat(inst.getStorageKey()).isNull();
        assertThat(inst.getGzipStorageKey()).isNull();
        assertThat(inst.isEncodingsChecked()).isFalse();
        verify(storage).delete("t/1.1/s/1.1.1.dcm");
        verify(storage).delete("t/1.1/s/1.1.1.dcm.gz");
    }

    @Test
    void cloudCopyIsKeptWhenTheHospitalServerIsMissingImages() {
        Study s = study("1.1", CloudStatus.PENDING_DELETE);
        s.setEdgeGatewayId(gateway.getId());

        service.confirmHeld(gateway, "1.1", 2);
        assertThat(s.getCloudStatus()).isEqualTo(CloudStatus.AVAILABLE);
        assertThat(s.getEdgeEvictedAt()).isNull();

        s.setCloudStatus(CloudStatus.PENDING_DELETE);
        service.confirmHeld(gateway, "1.1", 0);
        assertThat(s.getCloudStatus()).isEqualTo(CloudStatus.AVAILABLE);
        assertThat(s.getEdgeEvictedAt()).isNotNull();
        verifyNoInteractions(storage);
    }

    @Test
    void anotherGatewayCannotConfirmOrEvict() {
        Study s = study("1.1", CloudStatus.PENDING_DELETE);
        s.setEdgeGatewayId(UUID.randomUUID());
        service.confirmHeld(gateway, "1.1", 3);
        service.markEvicted(gateway, List.of("1.1"));
        assertThat(s.getCloudStatus()).isEqualTo(CloudStatus.PENDING_DELETE);
        assertThat(s.getEdgeEvictedAt()).isNull();
        verifyNoInteractions(storage);
    }

    @Test
    void requestingAgainCancelsAPendingRemoval() {
        Study s = study("1.1", CloudStatus.PENDING_DELETE);
        s.setEdgeGatewayId(gateway.getId());
        login(Role.RADIOLOGIST, null);
        assertThat(service.requestCloudCopy(s.getId()).cloudStatus()).isEqualTo(CloudStatus.AVAILABLE);
        assertThat(s.getCloudRequestedAt()).isNotNull();
    }

    @Test
    void evictionStopsAPendingUpload() {
        Study s = study("1.1", CloudStatus.REQUESTED);
        s.setEdgeGatewayId(gateway.getId());
        service.markEvicted(gateway, List.of("1.1"));
        assertThat(s.getEdgeEvictedAt()).isNotNull();
        assertThat(s.getCloudStatus()).isEqualTo(CloudStatus.EDGE_ONLY);
        assertThat(ImageLocationService.onHospitalServer(s)).isFalse();
    }
}
