package com.telerad.order;

import com.telerad.common.StudyPriority;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "imaging_orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImagingOrder {
    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "accession_number", nullable = false, length = 64)
    private String accessionNumber;

    /** The registered patient (patients.id). */
    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(nullable = false, length = 16)
    private String modality;

    /** The procedure from the central list; its name is copied to procedureDescription. */
    @Column(name = "procedure_id")
    private UUID procedureId;

    @Column(name = "procedure_description")
    private String procedureDescription;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private StudyPriority priority = StudyPriority.ROUTINE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private Laterality laterality = Laterality.NOT_APPLICABLE;

    /** Instructions for the MRT, printed on the slip (e.g. "Patient on wheelchair"). */
    @Column(name = "additional_instructions", columnDefinition = "TEXT")
    private String additionalInstructions;

    @Column(name = "referring_physician")
    private String referringPhysician;

    @Column(name = "clinical_info", columnDefinition = "TEXT")
    private String clinicalInfo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private OrderSource source = OrderSource.MANUAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private OrderStatus status = OrderStatus.SCHEDULED;

    @Column(name = "scheduled_date", nullable = false)
    private LocalDate scheduledDate;

    /** The linked study, once images arrived and matched. */
    @Column(name = "study_id")
    private UUID studyId;

    @Column(name = "arrived_at")
    private Instant arrivedAt;

    @Column(name = "scanning_at")
    private Instant scanningAt;

    @Column(name = "scanned_at")
    private Instant scannedAt;

    @Column(name = "ready_at")
    private Instant readyAt;

    @Column(name = "mrt_confirmed_at")
    private Instant mrtConfirmedAt;

    @Column(name = "mrt_confirmed_by")
    private UUID mrtConfirmedBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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
