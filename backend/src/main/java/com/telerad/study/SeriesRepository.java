package com.telerad.study;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface SeriesRepository extends JpaRepository<Series, UUID> {
    Optional<Series> findByStudyIdAndSeriesInstanceUid(UUID studyId, String seriesInstanceUid);
}
