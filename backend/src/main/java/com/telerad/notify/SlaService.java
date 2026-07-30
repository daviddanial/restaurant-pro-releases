package com.telerad.notify;

import com.telerad.common.StudyPriority;
import com.telerad.common.StudyStatus;
import com.telerad.config.TeleradProperties;
import com.telerad.study.Study;
import com.telerad.study.StudyRepository;
import com.telerad.tenant.Tenant;
import com.telerad.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SlaService {
    private final StudyRepository studyRepository;
    private final TenantRepository tenantRepository;
    private final NotificationService notificationService;
    private final TeleradProperties properties;

    @Transactional
    public void applySlaDeadline(Study study) {
        if (study.getSlaDueAt() != null) return;
        Instant readyAt = study.getReadyAt() != null ? study.getReadyAt() : Instant.now();
        int minutes = resolveMinutes(study);
        study.setSlaDueAt(readyAt.plus(minutes, ChronoUnit.MINUTES));
        study.setSlaNotified(false);
        study.setSlaBreachedAt(null);
    }

    public int resolveMinutes(Study study) {
        Tenant tenant = tenantRepository.findById(study.getTenantId()).orElse(null);
        StudyPriority priority = study.getPriority() == null ? StudyPriority.ROUTINE : study.getPriority();
        if (tenant != null) {
            return switch (priority) {
                case STAT -> tenant.getSlaStatMinutes();
                case URGENT -> tenant.getSlaUrgentMinutes();
                case ROUTINE -> tenant.getSlaRoutineMinutes();
            };
        }
        var sla = properties.getSla();
        return switch (priority) {
            case STAT -> sla.getStatMinutes();
            case URGENT -> sla.getUrgentMinutes();
            case ROUTINE -> sla.getRoutineMinutes();
        };
    }

    public boolean isOverdue(Study study) {
        if (study.getSlaDueAt() == null) return false;
        if (study.getStatus() == StudyStatus.REPORTED || study.getStatus() == StudyStatus.DELIVERED) {
            return false;
        }
        return Instant.now().isAfter(study.getSlaDueAt());
    }

    @Scheduled(fixedDelayString = "${telerad.sla.check-interval-ms:60000}")
    @Transactional
    public void checkBreaches() {
        if (!properties.getSla().isEnabled()) return;
        Instant now = Instant.now();
        List<Study> open = studyRepository.findByStatusInAndSlaDueAtBeforeAndSlaNotifiedFalse(
                EnumSet.of(StudyStatus.READY, StudyStatus.IN_PROGRESS), now);
        for (Study study : open) {
            study.setSlaBreachedAt(now);
            study.setSlaNotified(true);
            studyRepository.save(study);
            log.warn("SLA breached for study {} due {}", study.getId(), study.getSlaDueAt());
            notificationService.notifySlaOverdue(study);
        }
    }
}
