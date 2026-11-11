package com.telerad.annotation;

import com.telerad.audit.AuditService;
import com.telerad.auth.UserAccountRepository;
import com.telerad.auth.UserPrincipal;
import com.telerad.common.Role;
import com.telerad.storage.ObjectStorageService;
import com.telerad.study.Study;
import com.telerad.study.StudyRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StudyAnnotationServiceTest {
    @Mock StudyMeasurementRepository measurementRepository;
    @Mock StudyKeyImageRepository keyImageRepository;
    @Mock StudyRepository studyRepository;
    @Mock UserAccountRepository userRepository;
    @Mock ObjectStorageService storage;
    @Mock AuditService auditService;
    @InjectMocks StudyAnnotationService service;

    final UUID tenantA = UUID.randomUUID();
    final UUID tenantB = UUID.randomUUID();
    final UUID studyId = UUID.randomUUID();
    final UUID radA = UUID.randomUUID();
    final UUID radB = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        Study study = new Study();
        study.setId(studyId);
        study.setTenantId(tenantA);
        when(studyRepository.findById(studyId)).thenReturn(Optional.of(study));
        when(userRepository.findAllById(any())).thenReturn(List.of());
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void login(UUID id, Role role, UUID tenant) {
        UserPrincipal p = new UserPrincipal(id, "u@x", "User", role, tenant, null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(p, null, p.authorities()));
    }

    private StudyAnnotationService.MeasurementRequest req() {
        return new StudyAnnotationService.MeasurementRequest("1.2.3", "1.2.3.4", 1, "Length",
                "{\"points\":[]}", "Length 23.4 mm", "Axial, image 5");
    }

    @Test
    void radiologistCreatesMeasurementAndItIsAudited() {
        login(radA, Role.RADIOLOGIST, null);
        UUID id = UUID.randomUUID();
        when(measurementRepository.findById(id)).thenReturn(Optional.empty());

        var dto = service.upsertMeasurement(studyId, id, req());

        assertThat(dto.mine()).isTrue();
        assertThat(dto.summary()).isEqualTo("Length 23.4 mm");
        verify(measurementRepository).save(argThat(m -> m.getCreatedBy().equals(radA) && m.getTenantId().equals(tenantA)));
        verify(auditService).record(eq(tenantA), eq(radA), eq("MEASUREMENT_ADD"), any(), eq(id.toString()), any(), any());
    }

    @Test
    void anotherRadiologistCannotChangeOrDeleteIt() {
        UUID id = UUID.randomUUID();
        StudyMeasurement m = StudyMeasurement.builder().id(id).studyId(studyId).tenantId(tenantA)
                .createdBy(radA).createdAt(Instant.now()).updatedAt(Instant.now()).dataJson("{}").toolName("Length")
                .seriesInstanceUid("1").sopInstanceUid("2").frameNumber(1).build();
        when(measurementRepository.findById(id)).thenReturn(Optional.of(m));
        login(radB, Role.RADIOLOGIST, null);

        assertThatThrownBy(() -> service.upsertMeasurement(studyId, id, req()))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.deleteMeasurement(studyId, id))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(measurementRepository, never()).delete(any());

        login(UUID.randomUUID(), Role.ADMIN, null); // admin may
        service.deleteMeasurement(studyId, id);
        verify(measurementRepository).delete(m);
    }

    @Test
    void hospitalUserOfAnotherHospitalCannotSeeThem() {
        login(UUID.randomUUID(), Role.HOSPITAL_USER, tenantB);
        assertThatThrownBy(() -> service.listMeasurements(studyId))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.listKeyImages(studyId)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void ownHospitalUserCanSeeButNotAdd() {
        login(UUID.randomUUID(), Role.HOSPITAL_USER, tenantA);
        when(measurementRepository.findByStudyIdOrderByCreatedAtAsc(studyId)).thenReturn(List.of());
        assertThat(service.listMeasurements(studyId)).isEmpty();
        assertThatThrownBy(() -> service.upsertMeasurement(studyId, UUID.randomUUID(), req()))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void deletingAnUnsavedMeasurementIsANoOp() {
        login(radA, Role.RADIOLOGIST, null);
        UUID id = UUID.randomUUID();
        when(measurementRepository.findById(id)).thenReturn(Optional.empty());
        service.deleteMeasurement(studyId, id);
        verify(measurementRepository, never()).delete(any());
        verifyNoInteractions(auditService);
    }

    @Test
    void rejectsBadInput() {
        login(radA, Role.RADIOLOGIST, null);
        var badTool = new StudyAnnotationService.MeasurementRequest("1", "2", 1, "Len gth;drop", "{}", null, null);
        assertThatThrownBy(() -> service.upsertMeasurement(studyId, UUID.randomUUID(), badTool))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        String huge = "x".repeat(StudyAnnotationService.MAX_DATA_CHARS + 1);
        var tooBig = new StudyAnnotationService.MeasurementRequest("1", "2", 1, "Length", huge, null, null);
        assertThatThrownBy(() -> service.upsertMeasurement(studyId, UUID.randomUUID(), tooBig)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void snapshotMustReallyBeAJpegOrPng() {
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};
        var ok = StudyAnnotationService.decode("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg));
        assertThat(ok.contentType()).isEqualTo("image/jpeg");
        assertThatThrownBy(() -> StudyAnnotationService.decode("data:image/png;base64," + Base64.getEncoder().encodeToString(jpeg)))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> StudyAnnotationService.decode("data:text/html;base64,PGh0bWw+"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void keyImageIsStoredUnderTheStudyTenant() {
        login(radA, Role.RADIOLOGIST, null);
        when(keyImageRepository.findByStudyIdAndSopInstanceUidAndFrameNumber(studyId, "1.2.3.4", 1)).thenReturn(Optional.empty());
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3};
        var dto = service.addKeyImage(studyId, new StudyAnnotationService.KeyImageRequest("1.2.3", "1.2.3.4", 1, 40.0, 400.0,
                "Axial, image 5", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg)));
        assertThat(dto.hasSnapshot()).isTrue();
        verify(storage).put(startsWith(tenantA + "/key-images/" + studyId + "/"), any(byte[].class), eq("image/jpeg"));
        verify(auditService).record(eq(tenantA), eq(radA), eq("KEY_IMAGE_ADD"), any(), any(), any(), any());
    }
}
