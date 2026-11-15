package com.telerad.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProcedureRepository extends JpaRepository<Procedure, UUID> {
    List<Procedure> findAllByOrderByModalityAscSortOrderAscNameAsc();

    List<Procedure> findByActiveTrueOrderByModalityAscSortOrderAscNameAsc();

    Optional<Procedure> findFirstByModalityAndNameIgnoreCase(String modality, String name);

    Optional<Procedure> findFirstByModalityOrderBySortOrderDesc(String modality);
}
