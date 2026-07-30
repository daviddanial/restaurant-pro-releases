package com.telerad.tenant;

import com.telerad.common.DicomDirection;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "dicom_nodes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DicomNode {
    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "ae_title", nullable = false, unique = true, length = 16)
    private String aeTitle;

    private String host;
    private Integer port;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private DicomDirection direction;

    @Column(name = "allowed_ip", length = 64)
    private String allowedIp;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }
}
