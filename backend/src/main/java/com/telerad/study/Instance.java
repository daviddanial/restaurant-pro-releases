package com.telerad.study;

import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;

@Entity
@Table(name = "instances")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Instance {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "series_id", nullable = false)
    private Series series;

    @Column(name = "sop_instance_uid", nullable = false, length = 128)
    private String sopInstanceUid;

    @Column(name = "sop_class_uid", length = 128)
    private String sopClassUid;

    @Column(name = "instance_number")
    private Integer instanceNumber;

    private Integer rows;
    private Integer columns;

    @Column(name = "number_of_frames", nullable = false)
    @Builder.Default
    private int numberOfFrames = 1;

    /** Frames per second (DICOM CineRate / RecommendedDisplayFrameRate). */
    @Column(name = "cine_rate")
    private Double cineRate;

    /** Inter-frame delay in milliseconds (DICOM FrameTime). */
    @Column(name = "frame_time")
    private Double frameTime;

    /** ImagePositionPatient as "x,y,z" (mm). */
    @Column(name = "image_position_patient", length = 128)
    private String imagePositionPatient;

    /** ImageOrientationPatient as "r0,r1,r2,c0,c1,c2". */
    @Column(name = "image_orientation_patient", length = 256)
    private String imageOrientationPatient;

    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    @Column(name = "transfer_syntax_uid", length = 128)
    private String transferSyntaxUid;

    @Column(name = "thumbnail_key", length = 512)
    private String thumbnailKey;

    /** gzip copy of the object at {@link #storageKey}, served when the client accepts gzip. */
    @Column(name = "gzip_storage_key", length = 512)
    private String gzipStorageKey;

    /** True once the real transfer syntax was read and the gzip copy made or skipped. */
    @Column(name = "encodings_checked", nullable = false)
    @Builder.Default
    private boolean encodingsChecked = false;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
    }
}
