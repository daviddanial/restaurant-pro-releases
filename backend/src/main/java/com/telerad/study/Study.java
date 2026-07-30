package com.telerad.study;

import com.telerad.common.StudyPriority;
import com.telerad.common.StudySource;
import com.telerad.common.StudyStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "studies")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Study {
    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "patient_id")
    private UUID patientId;

    @Column(name = "study_instance_uid", nullable = false, length = 128)
    private String studyInstanceUid;

    @Column(name = "accession_number", length = 64)
    private String accessionNumber;

    @Column(name = "study_description", length = 512)
    private String studyDescription;

    @Column(length = 16)
    private String modality;

    @Column(name = "study_date")
    private LocalDate studyDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private StudyPriority priority = StudyPriority.ROUTINE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private StudyStatus status = StudyStatus.RECEIVED;

    @Column(name = "assigned_to")
    private UUID assignedTo;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "ready_at")
    private Instant readyAt;

    @Column(name = "reported_at")
    private Instant reportedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "sla_due_at")
    private Instant slaDueAt;

    @Column(name = "sla_breached_at")
    private Instant slaBreachedAt;

    @Column(name = "sla_notified", nullable = false)
    @Builder.Default
    private boolean slaNotified = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private StudySource source = StudySource.UPLOAD;

    @Column(name = "instance_count", nullable = false)
    @Builder.Default
    private int instanceCount = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "study", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Series> seriesList = new ArrayList<>();

    @Version
    private Long version;

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
