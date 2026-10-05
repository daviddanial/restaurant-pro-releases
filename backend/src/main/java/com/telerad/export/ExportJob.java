package com.telerad.export;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "export_jobs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExportJob {
    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "study_id", nullable = false)
    private UUID studyId;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ExportScope scope;

    @Column(name = "series_instance_uid", length = 128)
    private String seriesInstanceUid;

    @Column(name = "sop_instance_uid", length = 128)
    private String sopInstanceUid;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ExportJobStatus status;

    @Column(name = "total_objects", nullable = false)
    @Builder.Default
    private int totalObjects = 0;

    @Column(name = "completed_objects", nullable = false)
    @Builder.Default
    private int completedObjects = 0;

    @Column(name = "result_key", length = 512)
    private String resultKey;

    @Column(name = "error_message", length = 1024)
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
        if (status == null) status = ExportJobStatus.PENDING;
    }
}
