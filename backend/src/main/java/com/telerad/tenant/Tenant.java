package com.telerad.tenant;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenants")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Tenant {
    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "webhook_url", length = 1024)
    private String webhookUrl;

    @Column(name = "notify_email", length = 255)
    private String notifyEmail;

    @Column(name = "sla_stat_minutes", nullable = false)
    @Builder.Default
    private int slaStatMinutes = 60;

    @Column(name = "sla_urgent_minutes", nullable = false)
    @Builder.Default
    private int slaUrgentMinutes = 240;

    @Column(name = "sla_routine_minutes", nullable = false)
    @Builder.Default
    private int slaRoutineMinutes = 1440;

    @Enumerated(EnumType.STRING)
    @Column(name = "upload_policy", nullable = false, length = 16)
    @Builder.Default
    private com.telerad.common.UploadPolicy uploadPolicy = com.telerad.common.UploadPolicy.ALL;

    /** JSON list of rules that request a cloud copy automatically (see AutoSendRules). */
    @Column(name = "auto_send_rules", columnDefinition = "TEXT")
    private String autoSendRules;

    @Column(name = "priors_to_send", nullable = false)
    @Builder.Default
    private int priorsToSend = 1;

    /** Days after the report is signed before the cloud copy is removed; null keeps it. */
    @Column(name = "cloud_retention_days")
    private Integer cloudRetentionDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "edge_storage_mode", nullable = false, length = 16)
    @Builder.Default
    private com.telerad.common.EdgeStorageMode edgeStorageMode = com.telerad.common.EdgeStorageMode.ARCHIVE;

    /** Days the hospital server keeps a study; null keeps it for good. */
    @Column(name = "edge_local_retention_days")
    private Integer edgeLocalRetentionDays;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }
}
