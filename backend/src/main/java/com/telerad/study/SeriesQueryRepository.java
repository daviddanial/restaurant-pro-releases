package com.telerad.study;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.UUID;

public interface SeriesQueryRepository extends JpaRepository<Series, UUID> {
    @Query("SELECT s FROM Series s WHERE s.study.id = :studyId ORDER BY s.seriesNumber ASC")
    List<Series> findByStudyIdOrdered(@Param("studyId") UUID studyId);
}
