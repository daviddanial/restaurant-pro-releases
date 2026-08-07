package com.telerad.ingest;

import com.telerad.audit.AuditService;
import com.telerad.auth.UserPrincipal;
import com.telerad.common.Role;
import com.telerad.common.StudyPriority;
import com.telerad.storage.ObjectStorageService;
import com.telerad.study.StudyRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UploadServiceTest {
    @Mock ObjectStorageService storage;
    @Mock IngestPublisher publisher;
    @Mock StudyRepository studyRepository;
    @Mock AuditService auditService;
    @Mock IngestWorker ingestWorker;
    @InjectMocks UploadService uploadService;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(Role role, UUID tenantId) {
        UserPrincipal p = new UserPrincipal(UUID.randomUUID(), "u@t.local", "U", role, tenantId, "DEMO");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(p, null, p.authorities()));
    }

    @Test
    void upload_hospitalUserCannotUploadForOtherHospital() {
        UUID own = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        authenticate(Role.HOSPITAL_USER, own);
        MockMultipartFile file = new MockMultipartFile("files", "x.dcm", "application/dicom", new byte[]{1, 2, 3});

        assertThatThrownBy(() -> uploadService.upload(other, new MockMultipartFile[]{file}, StudyPriority.STAT))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException rse = (ResponseStatusException) ex;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(rse.getReason()).contains("Cannot upload");
                });
    }

    @Test
    void upload_adminCanUploadForAnyHospital() {
        UUID hospitalId = UUID.randomUUID();
        authenticate(Role.ADMIN, null);
        MockMultipartFile file = new MockMultipartFile("files", "bad.dcm", "application/dicom", new byte[]{1, 2, 3});

        Map<String, Object> result = uploadService.upload(hospitalId, new MockMultipartFile[]{file}, StudyPriority.ROUTINE);

        assertThat(result.get("tenantId")).isEqualTo(hospitalId);
        assertThat(result.get("instancesStored")).isEqualTo(0);
        @SuppressWarnings("unchecked")
        var errors = (java.util.List<String>) result.get("errors");
        assertThat(errors).isNotEmpty();
        verify(auditService).record(eq(hospitalId), any(), eq("UPLOAD"), eq("Study"), any(), any(), isNull());
    }

    @Test
    void upload_hospitalUserOwnHospital_collectsErrorsForInvalidDicom() {
        UUID hospitalId = UUID.randomUUID();
        authenticate(Role.HOSPITAL_USER, hospitalId);
        MockMultipartFile file = new MockMultipartFile("files", "bad.dcm", "application/dicom", "not-dicom".getBytes());

        Map<String, Object> result = uploadService.upload(hospitalId, new MockMultipartFile[]{file}, StudyPriority.URGENT);

        assertThat(result.get("tenantId")).isEqualTo(hospitalId);
        assertThat(result.get("instancesStored")).isEqualTo(0);
        verify(storage, never()).put(anyString(), any(), anyString());
    }
}
