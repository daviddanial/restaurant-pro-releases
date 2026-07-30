package com.telerad.study;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InstanceRepository extends JpaRepository<Instance, UUID> {
    Optional<Instance> findBySopInstanceUid(String sopInstanceUid);
    List<Instance> findBySeriesIdOrderByInstanceNumberAsc(UUID seriesId);
}
