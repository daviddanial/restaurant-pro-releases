package com.telerad.gateway;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "gateways")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Gateway {
    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 256)
    private String location;

    @Column(name = "gateway_role", nullable = false, length = 32)
    @Builder.Default
    private String gatewayRole = "PRIMARY";

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "access_token_hash", length = 100)
    private String accessTokenHash;

    @Column(name = "cert_fingerprint", length = 128)
    private String certFingerprint;

    @Column(name = "cert_serial", length = 64)
    private String certSerial;

    @Column(name = "cert_not_before")
    private Instant certNotBefore;

    @Column(name = "cert_not_after")
    private Instant certNotAfter;

    @Column(name = "enrolled_at")
    private Instant enrolledAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "disabled_at")
    private Instant disabledAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "last_heartbeat_at")
    private Instant lastHeartbeatAt;

    @Column(name = "last_heartbeat_payload", columnDefinition = "TEXT")
    private String lastHeartbeatPayload;

    @Column(name = "last_successful_upload_at")
    private Instant lastSuccessfulUploadAt;

    /** Address browsers on the hospital network use for the image service (reported by the agent). */
    @Column(name = "lan_url", length = 512)
    private String lanUrl;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
        if (gatewayRole == null || gatewayRole.isBlank()) gatewayRole = "PRIMARY";
    }
}
