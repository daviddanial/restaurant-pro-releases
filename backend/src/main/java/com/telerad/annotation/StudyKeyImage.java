package com.telerad.annotation;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

/** A slice the radiologist marked as important, with a JPEG snapshot of it as displayed. */
@Entity
@Table(name = "study_key_images")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StudyKeyImage {
    @Id
    private UUID id;

    @Column(name = "study_id", nullable = false)
    private UUID studyId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "series_instance_uid", nullable = false, length = 128)
    private String seriesInstanceUid;

    @Column(name = "sop_instance_uid", nullable = false, length = 128)
    private String sopInstanceUid;

    @Column(name = "frame_number", nullable = false)
    private int frameNumber;

    @Column(name = "window_center")
    private Double windowCenter;

    @Column(name = "window_width")
    private Double windowWidth;

    @Column(length = 255)
    private String location;

    @Column(name = "snapshot_storage_key", length = 512)
    private String snapshotStorageKey;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
