package com.telerad.notify;

import com.telerad.common.StudyPriority;
import com.telerad.common.StudyStatus;
import com.telerad.config.TeleradProperties;
import com.telerad.study.Study;
import com.telerad.study.StudyRepository;
import com.telerad.tenant.Tenant;
import com.telerad.tenant.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SlaServiceTest {
    @Mock StudyRepository studyRepository;
    @Mock TenantRepository tenantRepository;
    @Mock NotificationService notificationService;
    @Mock TeleradProperties properties;
    @InjectMocks SlaService slaService;

    private UUID tenantId;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        tenant = Tenant.builder()
                .id(tenantId)
                .code("DEMO")
                .name("Demo")
                .slaStatMinutes(30)
                .slaUrgentMinutes(120)
                .slaRoutineMinutes(720)
                .build();
        TeleradProperties.Sla sla = new TeleradProperties.Sla();
        sla.setEnabled(true);
        sla.setStatMinutes(60);
        sla.setUrgentMinutes(240);
        sla.setRoutineMinutes(1440);
        lenient().when(properties.getSla()).thenReturn(sla);
    }

    @Test
    void resolveMinutes_usesTenantOverrides() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        Study study = Study.builder().tenantId(tenantId).priority(StudyPriority.STAT).build();
        assertThat(slaService.resolveMinutes(study)).isEqualTo(30);

        study.setPriority(StudyPriority.URGENT);
        assertThat(slaService.resolveMinutes(study)).isEqualTo(120);

        study.setPriority(StudyPriority.ROUTINE);
        assertThat(slaService.resolveMinutes(study)).isEqualTo(720);
    }

    @Test
    void resolveMinutes_fallsBackToDefaultsWhenNoTenant() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());
        Study study = Study.builder().tenantId(tenantId).priority(StudyPriority.STAT).build();
        assertThat(slaService.resolveMinutes(study)).isEqualTo(60);
    }

    @Test
    void resolveMinutes_nullPriorityTreatedAsRoutine() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        Study study = Study.builder().tenantId(tenantId).priority(null).build();
        assertThat(slaService.resolveMinutes(study)).isEqualTo(720);
    }

    @Test
    void applySlaDeadline_setsDueFromReadyAt() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        Instant ready = Instant.parse("2024-01-01T10:00:00Z");
        Study study = Study.builder()
                .tenantId(tenantId)
                .priority(StudyPriority.STAT)
                .readyAt(ready)
                .build();

        slaService.applySlaDeadline(study);

        assertThat(study.getSlaDueAt()).isEqualTo(ready.plus(30, ChronoUnit.MINUTES));
        assertThat(study.isSlaNotified()).isFalse();
        assertThat(study.getSlaBreachedAt()).isNull();
    }

    @Test
    void applySlaDeadline_noopIfAlreadySet() {
        Instant existing = Instant.parse("2024-06-01T00:00:00Z");
        Study study = Study.builder().tenantId(tenantId).slaDueAt(existing).build();
        slaService.applySlaDeadline(study);
        assertThat(study.getSlaDueAt()).isEqualTo(existing);
        verifyNoInteractions(tenantRepository);
    }

    @Test
    void isOverdue_falseWhenNoDueOrAlreadyReported() {
        Study noDue = Study.builder().status(StudyStatus.READY).build();
        assertThat(slaService.isOverdue(noDue)).isFalse();

        Study reported = Study.builder()
                .status(StudyStatus.REPORTED)
                .slaDueAt(Instant.now().minusSeconds(60))
                .build();
        assertThat(slaService.isOverdue(reported)).isFalse();

        Study delivered = Study.builder()
                .status(StudyStatus.DELIVERED)
                .slaDueAt(Instant.now().minusSeconds(60))
                .build();
        assertThat(slaService.isOverdue(delivered)).isFalse();
    }

    @Test
    void isOverdue_trueWhenPastDueAndOpen() {
        Study study = Study.builder()
                .status(StudyStatus.READY)
                .slaDueAt(Instant.now().minusSeconds(10))
                .build();
        assertThat(slaService.isOverdue(study)).isTrue();
    }

    @Test
    void checkBreaches_marksAndNotifies() {
        Study study = Study.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .status(StudyStatus.READY)
                .slaDueAt(Instant.now().minusSeconds(5))
                .slaNotified(false)
                .build();
        when(studyRepository.findByStatusInAndSlaDueAtBeforeAndSlaNotifiedFalse(
                eq(EnumSet.of(StudyStatus.READY, StudyStatus.IN_PROGRESS)), any(Instant.class)))
                .thenReturn(List.of(study));
        when(studyRepository.save(study)).thenReturn(study);

        slaService.checkBreaches();

        assertThat(study.isSlaNotified()).isTrue();
        assertThat(study.getSlaBreachedAt()).isNotNull();
        verify(notificationService).notifySlaOverdue(study);
        verify(studyRepository).save(study);
    }

    @Test
    void checkBreaches_skipsWhenDisabled() {
        properties.getSla().setEnabled(false);
        slaService.checkBreaches();
        verify(studyRepository, never()).findByStatusInAndSlaDueAtBeforeAndSlaNotifiedFalse(any(), any());
    }
}
