package com.telerad.gateway;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GatewayRepository extends JpaRepository<Gateway, UUID> {
    List<Gateway> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    Optional<Gateway> findByIdAndActiveTrue(UUID id);
}
