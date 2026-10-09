package com.telerad.gateway;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "gateway_ingest_audit")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GatewayIngestAudit {
    @Id
    private UUID id;

    @Column(name = "gateway_id", nullable = false)
    private UUID gatewayId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "request_id", nullable = false)
    private UUID requestId;

    @Column(name = "study_instance_uid", length = 128)
    private String studyInstanceUid;

    @Column(name = "series_instance_uid", length = 128)
    private String seriesInstanceUid;

    @Column(name = "sop_instance_uid", length = 128)
    private String sopInstanceUid;

    @Column(name = "cert_serial", length = 64)
    private String certSerial;

    @Column(name = "cert_fingerprint", length = 128)
    private String certFingerprint;

    @Column(name = "source_ip", length = 64)
    private String sourceIp;

    @Column(nullable = false, length = 32)
    private String outcome;

    @Column(name = "failure_class", length = 32)
    private String failureClass;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "legal_hold", nullable = false)
    @Builder.Default
    private boolean legalHold = false;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }
}
