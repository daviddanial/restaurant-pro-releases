package com.telerad.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {
    List<AuditEvent> findTop100ByTenantIdOrderByCreatedAtDesc(UUID tenantId);
    List<AuditEvent> findTop100ByOrderByCreatedAtDesc();
}
