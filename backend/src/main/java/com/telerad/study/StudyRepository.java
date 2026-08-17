package com.telerad.study;

import com.telerad.common.StudyStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StudyRepository extends JpaRepository<Study, UUID> {
    Optional<Study> findByTenantIdAndStudyInstanceUid(UUID tenantId, String studyInstanceUid);

    Optional<Study> findByStudyInstanceUid(String studyInstanceUid);

    List<Study> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    @Query("""
        SELECT s FROM Study s
        WHERE s.status IN :statuses
        AND (:modality IS NULL OR s.modality = :modality)
        AND (:priority IS NULL OR s.priority = :priority)
        ORDER BY s.priority ASC, s.readyAt ASC, s.createdAt ASC
        """)
    List<Study> findWorklist(@Param("statuses") List<StudyStatus> statuses,
                             @Param("modality") String modality,
                             @Param("priority") com.telerad.common.StudyPriority priority);

    List<Study> findByAssignedToAndStatusIn(UUID userId, List<StudyStatus> statuses);

    long countByAssignedToAndStatus(UUID assignedTo, StudyStatus status);

    List<Study> findByStatusInAndSlaDueAtBeforeAndSlaNotifiedFalse(
            java.util.Collection<StudyStatus> statuses, java.time.Instant before);
}
