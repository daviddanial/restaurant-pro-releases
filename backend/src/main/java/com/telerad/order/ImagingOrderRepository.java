package com.telerad.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ImagingOrderRepository extends JpaRepository<ImagingOrder, UUID> {
    Optional<ImagingOrder> findByTenantIdAndAccessionNumber(UUID tenantId, String accessionNumber);

    Optional<ImagingOrder> findByStudyId(UUID studyId);

    List<ImagingOrder> findByTenantIdAndScheduledDateOrderByCreatedAtAsc(UUID tenantId, LocalDate day);

    /** Open orders without images for a patient, modality and day (suggested match, rule 3). */
    @Query("""
        SELECT o FROM ImagingOrder o
        WHERE o.tenantId = :tenantId AND o.patientId IN :patientIds
        AND o.modality = :modality AND o.scheduledDate = :day
        AND o.studyId IS NULL AND o.status IN :open
        """)
    List<ImagingOrder> findOpenCandidates(@Param("tenantId") UUID tenantId,
                                          @Param("patientIds") Collection<UUID> patientIds,
                                          @Param("modality") String modality,
                                          @Param("day") LocalDate day,
                                          @Param("open") Collection<OrderStatus> open);

    /** Open orders a person can link an unmatched study to (recent days, no images yet). */
    @Query("""
        SELECT o FROM ImagingOrder o
        WHERE o.tenantId = :tenantId AND o.studyId IS NULL AND o.status IN :open
        AND o.scheduledDate >= :since
        ORDER BY o.scheduledDate DESC, o.createdAt DESC
        """)
    List<ImagingOrder> findLinkable(@Param("tenantId") UUID tenantId, @Param("since") LocalDate since,
                                    @Param("open") Collection<OrderStatus> open);

    /** Orders whose scan may have finished (images stopped arriving). */
    List<ImagingOrder> findByStatusAndStudyIdIsNotNull(OrderStatus status);

    /** Next number of a per-facility, per-day counter (atomic; must run in a read-write transaction). */
    @Query(value = """
        INSERT INTO accession_counters (tenant_id, day, value) VALUES (:tenantId, :day, 1)
        ON CONFLICT (tenant_id, day) DO UPDATE SET value = accession_counters.value + 1
        RETURNING value
        """, nativeQuery = true)
    int nextCounter(@Param("tenantId") UUID tenantId, @Param("day") LocalDate day);
}
