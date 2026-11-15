package com.telerad.order;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** One entry of the central imaging procedure list (KB note 15 §9), shared by all hospitals. */
@Entity
@Table(name = "procedures")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Procedure {
    @Id
    private UUID id;

    /** DICOM modality code: US, DX, RF, CT, MR, MG, BMD… */
    @Column(nullable = false, length = 16)
    private String modality;

    @Column(nullable = false)
    private String name;

    /** Switched-off procedures stay on old orders but are no longer offered. */
    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private int sortOrder = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
