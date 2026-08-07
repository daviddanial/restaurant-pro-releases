package com.telerad.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {
    @Mock AuditEventRepository repository;
    @InjectMocks AuditService auditService;

    @Test
    void record_persistsEvent() {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        auditService.record(tenantId, userId, "LOGIN", "User", userId.toString(), "detail", "127.0.0.1");

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).save(captor.capture());
        AuditEvent event = captor.getValue();
        assertThat(event.getTenantId()).isEqualTo(tenantId);
        assertThat(event.getUserId()).isEqualTo(userId);
        assertThat(event.getAction()).isEqualTo("LOGIN");
        assertThat(event.getEntityType()).isEqualTo("User");
        assertThat(event.getEntityId()).isEqualTo(userId.toString());
        assertThat(event.getDetail()).isEqualTo("detail");
        assertThat(event.getIpAddress()).isEqualTo("127.0.0.1");
    }
}
