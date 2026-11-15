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

    List<Study> findByTenantIdAndPatientIdAndStatusInOrderByStudyDateDescCreatedAtDesc(
            UUID tenantId, UUID patientId, java.util.Collection<StudyStatus> statuses);

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

    /**
     * Earlier studies for the same patient, most recent first. Same-modality studies sort
     * ahead of the rest so the caller can default to the closest comparison.
     */
    @Query("""
        SELECT s FROM Study s
        WHERE s.patientId = :patientId
        AND s.tenantId = :tenantId
        AND s.id <> :excludeId
        AND s.instanceCount > 0
        ORDER BY
          CASE WHEN s.modality = :modality THEN 0 ELSE 1 END ASC,
          s.studyDate DESC NULLS LAST,
          s.createdAt DESC
        """)
    List<Study> findPriors(@Param("patientId") UUID patientId,
                           @Param("tenantId") UUID tenantId,
                           @Param("excludeId") UUID excludeId,
                           @Param("modality") String modality);

    /** Studies a hospital server should act on (upload or confirm it still holds them). */
    List<Study> findByEdgeGatewayIdAndCloudStatusOrderByCloudRequestedAtAsc(
            UUID edgeGatewayId, com.telerad.common.CloudStatus cloudStatus);

    /**
     * Cloud copies whose retention ran out: reported before the cutoff, and not requested again
     * since. Only studies the hospital server still holds are candidates.
     */
    @Query("""
        SELECT s FROM Study s
        WHERE s.tenantId = :tenantId
        AND s.edgeGatewayId IS NOT NULL
        AND s.edgeEvictedAt IS NULL
        AND s.cloudStatus = com.telerad.common.CloudStatus.AVAILABLE
        AND s.status IN (com.telerad.common.StudyStatus.REPORTED, com.telerad.common.StudyStatus.DELIVERED)
        AND s.reportedAt IS NOT NULL AND s.reportedAt < :cutoff
        AND (s.cloudRequestedAt IS NULL OR s.cloudRequestedAt < :cutoff)
        """)
    List<Study> findCloudRetentionCandidates(@Param("tenantId") UUID tenantId,
                                             @Param("cutoff") java.time.Instant cutoff);

    List<Study> findByTenantIdAndMatchStateInOrderByCreatedAtAsc(
            UUID tenantId, java.util.Collection<com.telerad.order.MatchState> states);

    List<Study> findByAssignedToAndStatusIn(UUID userId, List<StudyStatus> statuses);

    long countByAssignedToAndStatus(UUID assignedTo, StudyStatus status);

    List<Study> findByStatusInAndSlaDueAtBeforeAndSlaNotifiedFalse(
            java.util.Collection<StudyStatus> statuses, java.time.Instant before);
}
