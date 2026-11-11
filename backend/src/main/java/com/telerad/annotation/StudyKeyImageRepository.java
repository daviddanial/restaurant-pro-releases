package com.telerad.annotation;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StudyKeyImageRepository extends JpaRepository<StudyKeyImage, UUID> {
    List<StudyKeyImage> findByStudyIdOrderByCreatedAtAsc(UUID studyId);

    Optional<StudyKeyImage> findByStudyIdAndSopInstanceUidAndFrameNumber(UUID studyId, String sopInstanceUid, int frameNumber);
}
