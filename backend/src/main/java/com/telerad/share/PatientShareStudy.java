package com.telerad.share;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.util.UUID;

@Entity
@Table(name = "patient_share_studies")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@IdClass(PatientShareStudy.Pk.class)
public class PatientShareStudy {
    @Id
    @Column(name = "share_id", nullable = false)
    private UUID shareId;

    @Id
    @Column(name = "study_id", nullable = false)
    private UUID studyId;

    /** MAIN = shared study; PRIOR = frozen comparison study at create time. */
    @Column(name = "role", nullable = false, length = 16)
    @Builder.Default
    private String role = "MAIN";

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Pk implements Serializable {
        private UUID shareId;
        private UUID studyId;
    }
}
