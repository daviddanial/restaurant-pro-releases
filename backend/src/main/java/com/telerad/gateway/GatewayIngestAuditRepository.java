package com.telerad.gateway;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface GatewayIngestAuditRepository extends JpaRepository<GatewayIngestAudit, UUID> {
    @Modifying
    @Query("DELETE FROM GatewayIngestAudit a WHERE a.createdAt < :cutoff AND a.legalHold = false")
    int deleteExpired(@Param("cutoff") Instant cutoff);
}
