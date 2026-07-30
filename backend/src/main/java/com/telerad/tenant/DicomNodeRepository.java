package com.telerad.tenant;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DicomNodeRepository extends JpaRepository<DicomNode, UUID> {
    Optional<DicomNode> findByAeTitleIgnoreCaseAndActiveTrue(String aeTitle);
    List<DicomNode> findByTenantId(UUID tenantId);
}
