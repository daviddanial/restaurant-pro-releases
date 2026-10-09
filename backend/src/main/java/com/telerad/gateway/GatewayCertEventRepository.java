package com.telerad.gateway;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface GatewayCertEventRepository extends JpaRepository<GatewayCertEvent, UUID> {
}
