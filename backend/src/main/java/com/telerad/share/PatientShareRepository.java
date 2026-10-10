package com.telerad.share;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PatientShareRepository extends JpaRepository<PatientShare, UUID> {
    List<PatientShare> findByTenantIdAndPatientIdOrderByCreatedAtDesc(UUID tenantId, UUID patientId);

    List<PatientShare> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    Optional<PatientShare> findByLinkTokenLookup(String linkTokenLookup);
}
