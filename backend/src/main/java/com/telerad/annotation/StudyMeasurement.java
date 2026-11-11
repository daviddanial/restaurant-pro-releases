package com.telerad.annotation;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;

/** A measurement drawn in the viewer, saved with the study. Geometry is opaque JSON from the viewer. */
@Entity
@Table(name = "study_measurements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StudyMeasurement {
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

    @Column(name = "tool_name", nullable = false, length = 64)
    private String toolName;

    @Column(name = "data_json", nullable = false, columnDefinition = "TEXT")
    private String dataJson;

    @Column(length = 500)
    private String summary;

    @Column(length = 255)
    private String location;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
