package com.telerad.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditService {
    private final AuditEventRepository repository;

    @Transactional
    public void record(UUID tenantId, UUID userId, String action, String entityType, String entityId, String detail, String ip) {
        repository.save(AuditEvent.builder()
                .tenantId(tenantId)
                .userId(userId)
                .action(action)
                .entityType(entityType)
                .entityId(entityId)
                .detail(detail)
                .ipAddress(ip)
                .build());
    }
}
