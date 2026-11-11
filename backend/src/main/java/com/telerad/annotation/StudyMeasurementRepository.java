package com.telerad.annotation;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface StudyMeasurementRepository extends JpaRepository<StudyMeasurement, UUID> {
    List<StudyMeasurement> findByStudyIdOrderByCreatedAtAsc(UUID studyId);
}
