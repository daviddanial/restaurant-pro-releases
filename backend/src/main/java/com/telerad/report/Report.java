package com.telerad.report;

import com.telerad.common.ReportStatus;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reports")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Report {
    @Id
    private UUID id;

    @Column(name = "study_id", nullable = false, unique = true)
    private UUID studyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private ReportStatus status = ReportStatus.DRAFT;

    @Column(columnDefinition = "TEXT")
    private String findings;

    @Column(columnDefinition = "TEXT")
    private String impression;

    @Column(nullable = false)
    @Builder.Default
    private boolean critical = false;

    @Column(nullable = false)
    @Builder.Default
    private int version = 1;

    @Column(name = "author_id")
    private UUID authorId;

    @Column(name = "signer_id")
    private UUID signerId;

    @Column(name = "signed_at")
    private Instant signedAt;

    @Column(name = "pdf_storage_key", length = 512)
    private String pdfStorageKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long optimisticLock;

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
