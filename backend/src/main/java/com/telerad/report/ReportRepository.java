package com.telerad.report;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReportRepository extends JpaRepository<Report, UUID> {
    Optional<Report> findByStudyId(UUID studyId);
    List<Report> findByStudyIdIn(List<UUID> studyIds);
}
