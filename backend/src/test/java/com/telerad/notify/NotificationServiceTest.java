package com.telerad.notify;

import com.telerad.auth.UserAccount;
import com.telerad.auth.UserAccountRepository;
import com.telerad.common.Role;
import com.telerad.common.StudyPriority;
import com.telerad.common.StudyStatus;
import com.telerad.config.TeleradProperties;
import com.telerad.study.Study;
import com.telerad.tenant.Tenant;
import com.telerad.tenant.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {
    @Mock NotificationEventRepository eventRepository;
    @Mock TenantRepository tenantRepository;
    @Mock UserAccountRepository userAccountRepository;
    @Mock RestClient.Builder restClientBuilder;

    private TeleradProperties properties;
    private NotificationService service;
    private Study study;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        properties = new TeleradProperties();
        properties.getNotify().setEmailEnabled(true);
        properties.getNotify().setWebhookEnabled(false);
        properties.getNotify().setFallbackEmail("ops@telerad.local");
        service = new NotificationService(eventRepository, tenantRepository, userAccountRepository,
                properties, restClientBuilder);

        UUID tenantId = UUID.randomUUID();
        tenant = Tenant.builder().id(tenantId).code("DEMO").name("Demo")
                .notifyEmail("notify@demo.local").build();
        study = Study.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .studyInstanceUid("1.2.3")
                .accessionNumber("ACC-9")
                .modality("MR")
                .status(StudyStatus.READY)
                .priority(StudyPriority.STAT)
                .build();

        when(eventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void notifyStudyReady_emailsActiveRadiologists() {
        when(tenantRepository.findById(study.getTenantId())).thenReturn(Optional.of(tenant));
        UserAccount rad = UserAccount.builder()
                .id(UUID.randomUUID()).email("rad@telerad.local").fullName("Rad")
                .role(Role.RADIOLOGIST).passwordHash("h").active(true).build();
        UserAccount inactive = UserAccount.builder()
                .id(UUID.randomUUID()).email("old@telerad.local").fullName("Old")
                .role(Role.RADIOLOGIST).passwordHash("h").active(false).build();
        when(userAccountRepository.findByRole(Role.RADIOLOGIST)).thenReturn(List.of(rad, inactive));

        service.notifyStudyReady(study);

        ArgumentCaptor<NotificationEvent> captor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(eventRepository, times(2)).save(captor.capture());
        NotificationEvent email = captor.getAllValues().stream()
                .filter(e -> "EMAIL".equals(e.getChannel())).findFirst().orElseThrow();
        assertThat(email.getEventType()).isEqualTo("STUDY_READY");
        assertThat(email.getStatus()).isEqualTo("SENT");
        assertThat(email.getRecipient()).isEqualTo("rad@telerad.local");
        assertThat(email.getPayload()).contains("ACC-9");
    }

    @Test
    void notifyStudyReady_usesFallbackWhenNoRadiologists() {
        when(tenantRepository.findById(study.getTenantId())).thenReturn(Optional.of(tenant));
        when(userAccountRepository.findByRole(Role.RADIOLOGIST)).thenReturn(List.of());

        service.notifyStudyReady(study);

        ArgumentCaptor<NotificationEvent> captor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(eventRepository, atLeastOnce()).save(captor.capture());
        NotificationEvent email = captor.getAllValues().stream()
                .filter(e -> "EMAIL".equals(e.getChannel())).findFirst().orElseThrow();
        assertThat(email.getRecipient()).isEqualTo("ops@telerad.local");
    }

    @Test
    void notifyReportSigned_criticalFiresExtraEvents() {
        when(tenantRepository.findById(study.getTenantId())).thenReturn(Optional.of(tenant));
        service.notifyReportSigned(study, "Pneumothorax", true, "{}", "pdf-key");

        ArgumentCaptor<NotificationEvent> captor = ArgumentCaptor.forClass(NotificationEvent.class);
        // REPORT_SIGNED email+webhook skip, CRITICAL_RESULT email+webhook skip = 4
        verify(eventRepository, times(4)).save(captor.capture());
        assertThat(captor.getAllValues())
                .filteredOn(e -> "EMAIL".equals(e.getChannel()))
                .extracting(NotificationEvent::getEventType)
                .containsExactly("REPORT_SIGNED", "CRITICAL_RESULT");
        NotificationEvent signedEmail = captor.getAllValues().stream()
                .filter(e -> "EMAIL".equals(e.getChannel()) && "REPORT_SIGNED".equals(e.getEventType()))
                .findFirst().orElseThrow();
        assertThat(signedEmail.getRecipient()).isEqualTo("notify@demo.local");
        assertThat(signedEmail.getPayload()).contains("[CRITICAL]");
    }

    @Test
    void notifySlaOverdue_skipsEmailWhenDisabled() {
        properties.getNotify().setEmailEnabled(false);
        when(tenantRepository.findById(study.getTenantId())).thenReturn(Optional.of(tenant));
        when(userAccountRepository.findByRole(Role.RADIOLOGIST)).thenReturn(List.of());

        service.notifySlaOverdue(study);

        ArgumentCaptor<NotificationEvent> captor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(eventRepository, atLeastOnce()).save(captor.capture());
        NotificationEvent email = captor.getAllValues().stream()
                .filter(e -> "EMAIL".equals(e.getChannel())).findFirst().orElseThrow();
        assertThat(email.getStatus()).isEqualTo("SKIPPED");
        assertThat(email.getErrorMessage()).contains("email channel disabled");
    }

    @Test
    void notifyStudyReady_skipsWebhookWhenDisabled() {
        properties.getNotify().setWebhookEnabled(false);
        when(tenantRepository.findById(study.getTenantId())).thenReturn(Optional.of(tenant));
        when(userAccountRepository.findByRole(Role.RADIOLOGIST)).thenReturn(List.of());

        service.notifyStudyReady(study);

        ArgumentCaptor<NotificationEvent> captor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(eventRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .filteredOn(e -> "WEBHOOK".equals(e.getChannel()))
                .first()
                .extracting(NotificationEvent::getStatus)
                .isEqualTo("SKIPPED");
    }
}
