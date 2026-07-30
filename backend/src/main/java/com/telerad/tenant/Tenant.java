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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }
}
