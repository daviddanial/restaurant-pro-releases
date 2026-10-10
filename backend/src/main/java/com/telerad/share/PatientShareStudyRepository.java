package com.telerad.share;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PatientShareStudyRepository extends JpaRepository<PatientShareStudy, PatientShareStudy.Pk> {
    List<PatientShareStudy> findByShareId(UUID shareId);

    boolean existsByShareIdAndStudyId(UUID shareId, UUID studyId);

    @Query("SELECT pss.studyId FROM PatientShareStudy pss WHERE pss.shareId = :shareId")
    List<UUID> findStudyIdsByShareId(@Param("shareId") UUID shareId);

    @Query("SELECT pss.studyId FROM PatientShareStudy pss WHERE pss.shareId = :shareId AND pss.role = :role")
    List<UUID> findStudyIdsByShareIdAndRole(@Param("shareId") UUID shareId, @Param("role") String role);

    Optional<PatientShareStudy> findByShareIdAndRole(UUID shareId, String role);
}
