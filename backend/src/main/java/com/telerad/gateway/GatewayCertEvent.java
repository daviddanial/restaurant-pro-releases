package com.telerad.gateway;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "gateway_cert_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GatewayCertEvent {
    @Id
    private UUID id;

    @Column(name = "gateway_id", nullable = false)
    private UUID gatewayId;

    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;

    @Column(name = "cert_serial", length = 64)
    private String certSerial;

    @Column(name = "cert_fingerprint", length = 128)
    private String certFingerprint;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }
}
