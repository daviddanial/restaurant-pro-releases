package com.telerad.report;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ReportDeliveryRepository extends JpaRepository<ReportDelivery, UUID> {
    List<ReportDelivery> findByReportId(UUID reportId);
}
