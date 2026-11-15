package com.telerad.study;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "patients")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Patient {
    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "patient_id", nullable = false, length = 128)
    private String patientId;

    @Column(name = "patient_name")
    private String patientName;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(length = 16)
    private String sex;

    /** Registered by reception: the image header never overwrites these demographics. */
    @Column(nullable = false)
    @Builder.Default
    private boolean registered = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }
}
