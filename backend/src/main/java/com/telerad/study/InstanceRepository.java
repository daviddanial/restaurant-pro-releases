package com.telerad.study;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InstanceRepository extends JpaRepository<Instance, UUID> {
    Optional<Instance> findBySopInstanceUid(String sopInstanceUid);
    List<Instance> findBySeriesIdOrderByInstanceNumberAsc(UUID seriesId);

    @Query("select i.id from Instance i where i.encodingsChecked = false and i.storageKey is not null order by i.id")
    List<UUID> findIdsWithUncheckedEncodings(Pageable page);

    @Query("select count(i) from Instance i where i.encodingsChecked = false and i.storageKey is not null")
    long countWithUncheckedEncodings();

    @Query("select i.storageKey from Instance i where i.id = :id")
    Optional<String> findStorageKeyById(@Param("id") UUID id);

    /** All instances of a study (cloud copy upload/removal). */
    @Query("select i from Instance i where i.series.study.id = :studyId")
    List<Instance> findByStudyId(@Param("studyId") UUID studyId);

    /** Instances of a study that have no cloud copy yet. */
    @Query("select count(i) from Instance i where i.series.study.id = :studyId and i.storageKey is null")
    long countWithoutCloudCopy(@Param("studyId") UUID studyId);

    @Query("select count(i) from Instance i where i.series.study.id = :studyId and i.storageKey is not null")
    long countWithCloudCopy(@Param("studyId") UUID studyId);

    @Modifying
    @Transactional
    @Query("update Instance i set i.transferSyntaxUid = :tsuid, i.gzipStorageKey = :gzipKey, "
            + "i.encodingsChecked = true where i.id = :id")
    int markEncodingsChecked(@Param("id") UUID id, @Param("tsuid") String tsuid, @Param("gzipKey") String gzipKey);
}
