package com.telerad.study;

import jakarta.persistence.*;
import lombok.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "series")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Series {
    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "study_id", nullable = false)
    private Study study;

    @Column(name = "series_instance_uid", nullable = false, length = 128)
    private String seriesInstanceUid;

    @Column(length = 16)
    private String modality;

    @Column(name = "series_number")
    private Integer seriesNumber;

    @Column(name = "series_description", length = 512)
    private String seriesDescription;

    @Column(name = "instance_count", nullable = false)
    @Builder.Default
    private int instanceCount = 0;

    @OneToMany(mappedBy = "series", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Instance> instances = new ArrayList<>();

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
    }
}
