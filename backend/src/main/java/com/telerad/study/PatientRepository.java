package com.telerad.study;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface PatientRepository extends JpaRepository<Patient, UUID> {
    Optional<Patient> findByTenantIdAndPatientId(UUID tenantId, String patientId);
}
