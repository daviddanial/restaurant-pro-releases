package com.telerad.gateway;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@RequiredArgsConstructor
@Slf4j
public class GatewayOpsScheduler {
    private final GatewayService gatewayService;
    private final GatewayProperties props;
    private final GatewayIngestAuditRepository ingestAuditRepository;
    private final Set<UUID> alertedMiss = ConcurrentHashMap.newKeySet();

    @Scheduled(fixedDelayString = "${telerad.gateway.heartbeat-check-ms:60000}")
    public void checkMissedHeartbeats() {
        Instant cutoff = Instant.now().minus(props.getHeartbeatMissSeconds(), ChronoUnit.SECONDS);
        List<Gateway> misses = gatewayService.findHeartbeatMisses(cutoff);
        for (Gateway g : misses) {
            if (alertedMiss.add(g.getId())) {
                log.warn("ALERT gateway_heartbeat_missed gatewayId={} tenantId={} lastHeartbeat={}",
                        g.getId(), g.getTenantId(), g.getLastHeartbeatAt());
            }
        }
        Set<UUID> stillMissing = ConcurrentHashMap.newKeySet();
        stillMissing.addAll(misses.stream().map(Gateway::getId).toList());
        for (UUID id : Set.copyOf(alertedMiss)) {
            if (!stillMissing.contains(id)) {
                alertedMiss.remove(id);
                log.info("ALERT gateway_heartbeat_recovered gatewayId={}", id);
            }
        }
    }

    @Scheduled(cron = "${telerad.gateway.audit-purge-cron:0 30 3 * * *}")
    @Transactional
    public void purgeIngestAudit() {
        int days = Math.max(365, props.getIngestAuditRetentionDays());
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        int n = ingestAuditRepository.deleteExpired(cutoff);
        if (n > 0) {
            log.info("Purged {} gateway_ingest_audit rows older than {} days (legal_hold exempt)", n, days);
        }
    }
}
