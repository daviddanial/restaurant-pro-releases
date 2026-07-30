package com.telerad.notify;

import com.telerad.auth.UserAccount;
import com.telerad.auth.UserAccountRepository;
import com.telerad.common.Role;
import com.telerad.config.TeleradProperties;
import com.telerad.study.Study;
import com.telerad.tenant.Tenant;
import com.telerad.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {
    private final NotificationEventRepository eventRepository;
    private final TenantRepository tenantRepository;
    private final UserAccountRepository userAccountRepository;
    private final TeleradProperties properties;
    private final RestClient.Builder restClientBuilder;

    public enum EventType {
        STUDY_READY,
        REPORT_SIGNED,
        SLA_OVERDUE,
        CRITICAL_RESULT
    }

    @Async
    @Transactional
    public void notifyStudyReady(Study study) {
        Tenant tenant = tenantRepository.findById(study.getTenantId()).orElse(null);
        String subject = "Study ready for reading: " + nullToDash(study.getAccessionNumber());
        Map<String, Object> body = basePayload(EventType.STUDY_READY, study, tenant);
        body.put("message", subject);
        body.put("priority", study.getPriority() == null ? null : study.getPriority().name());
        body.put("slaDueAt", study.getSlaDueAt() == null ? null : study.getSlaDueAt().toString());

        List<UserAccount> radiologists = userAccountRepository.findByRole(Role.RADIOLOGIST).stream()
                .filter(UserAccount::isActive).toList();
        String recipients = radiologists.stream().map(UserAccount::getEmail).collect(Collectors.joining(","));
        if (recipients.isBlank()) {
            recipients = properties.getNotify().getFallbackEmail();
        }
        fireEmail(EventType.STUDY_READY, study, tenant, recipients, subject, body);
        fireWebhook(EventType.STUDY_READY, study, tenant, body);
    }

    @Async
    @Transactional
    public void notifyReportSigned(Study study, String impression, boolean critical, String fhirJson, String pdfKey) {
        Tenant tenant = tenantRepository.findById(study.getTenantId()).orElse(null);
        String subject = (critical ? "[CRITICAL] " : "") + "Report signed: " + nullToDash(study.getAccessionNumber());
        Map<String, Object> body = basePayload(EventType.REPORT_SIGNED, study, tenant);
        body.put("message", subject);
        body.put("critical", critical);
        body.put("impression", impression);
        body.put("pdfStorageKey", pdfKey);
        body.put("fhir", fhirJson);

        String email = tenant != null && tenant.getNotifyEmail() != null
                ? tenant.getNotifyEmail()
                : properties.getNotify().getFallbackEmail();
        fireEmail(EventType.REPORT_SIGNED, study, tenant, email, subject, body);
        fireWebhook(EventType.REPORT_SIGNED, study, tenant, body);
        if (critical) {
            Map<String, Object> crit = new HashMap<>(body);
            crit.put("eventType", EventType.CRITICAL_RESULT.name());
            fireEmail(EventType.CRITICAL_RESULT, study, tenant, email, subject, crit);
            fireWebhook(EventType.CRITICAL_RESULT, study, tenant, crit);
        }
    }

    @Async
    @Transactional
    public void notifySlaOverdue(Study study) {
        Tenant tenant = tenantRepository.findById(study.getTenantId()).orElse(null);
        String subject = "SLA overdue: " + nullToDash(study.getAccessionNumber())
                + " (" + (study.getPriority() == null ? "?" : study.getPriority().name()) + ")";
        Map<String, Object> body = basePayload(EventType.SLA_OVERDUE, study, tenant);
        body.put("message", subject);
        body.put("slaDueAt", study.getSlaDueAt() == null ? null : study.getSlaDueAt().toString());
        body.put("priority", study.getPriority() == null ? null : study.getPriority().name());

        List<UserAccount> radiologists = userAccountRepository.findByRole(Role.RADIOLOGIST).stream()
                .filter(UserAccount::isActive).toList();
        String recipients = radiologists.stream().map(UserAccount::getEmail).collect(Collectors.joining(","));
        if (recipients.isBlank()) {
            recipients = properties.getNotify().getFallbackEmail();
        }
        fireEmail(EventType.SLA_OVERDUE, study, tenant, recipients, subject, body);
        fireWebhook(EventType.SLA_OVERDUE, study, tenant, body);
    }

    private Map<String, Object> basePayload(EventType type, Study study, Tenant tenant) {
        Map<String, Object> body = new HashMap<>();
        body.put("eventType", type.name());
        body.put("timestamp", Instant.now().toString());
        body.put("studyId", study.getId().toString());
        body.put("studyInstanceUid", study.getStudyInstanceUid());
        body.put("accessionNumber", study.getAccessionNumber());
        body.put("modality", study.getModality());
        body.put("status", study.getStatus() == null ? null : study.getStatus().name());
        body.put("tenantId", study.getTenantId().toString());
        body.put("tenantCode", tenant == null ? null : tenant.getCode());
        body.put("tenantName", tenant == null ? null : tenant.getName());
        return body;
    }

    private void fireEmail(EventType type, Study study, Tenant tenant, String recipient, String subject, Map<String, Object> body) {
        if (!properties.getNotify().isEmailEnabled()) {
            persist(type, study, tenant, "EMAIL", recipient, body, "SKIPPED", "email channel disabled");
            return;
        }
        // Stub transport: always "sends" by logging + persisting. Wire JavaMail here when SMTP is configured.
        String payload = "SUBJECT: " + subject + "\nTO: " + recipient + "\nBODY: " + body;
        log.info("[NOTIFY:EMAIL] event={} study={} to={} subject={}", type, study.getId(), recipient, subject);
        persist(type, study, tenant, "EMAIL", recipient, payload, "SENT", null);
    }

    private void fireWebhook(EventType type, Study study, Tenant tenant, Map<String, Object> body) {
        if (!properties.getNotify().isWebhookEnabled()) {
            persist(type, study, tenant, "WEBHOOK", null, body.toString(), "SKIPPED", "webhook channel disabled");
            return;
        }
        String url = null;
        if (tenant != null && tenant.getWebhookUrl() != null && !tenant.getWebhookUrl().isBlank()) {
            url = tenant.getWebhookUrl();
        } else if (properties.getNotify().getDefaultWebhookUrl() != null
                && !properties.getNotify().getDefaultWebhookUrl().isBlank()) {
            url = properties.getNotify().getDefaultWebhookUrl();
        }
        if (url == null) {
            persist(type, study, tenant, "WEBHOOK", null, body.toString(), "SKIPPED", "no webhook URL configured");
            return;
        }
        try {
            RestClient client = restClientBuilder.build();
            client.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("[NOTIFY:WEBHOOK] event={} study={} url={} OK", type, study.getId(), url);
            persist(type, study, tenant, "WEBHOOK", url, body.toString(), "SENT", null);
        } catch (Exception e) {
            // Stub still "fires": we attempted delivery and recorded the outcome.
            log.warn("[NOTIFY:WEBHOOK] event={} study={} url={} failed: {}", type, study.getId(), url, e.getMessage());
            persist(type, study, tenant, "WEBHOOK", url, body.toString(), "FAILED", e.getMessage());
        }
    }

    private void persist(EventType type, Study study, Tenant tenant, String channel, String recipient,
                         Object payload, String status, String error) {
        eventRepository.save(NotificationEvent.builder()
                .eventType(type.name())
                .studyId(study.getId())
                .tenantId(study.getTenantId())
                .channel(channel)
                .recipient(recipient)
                .payload(payload == null ? null : payload.toString())
                .status(status)
                .errorMessage(error)
                .build());
    }

    private static String nullToDash(String v) {
        return v == null || v.isBlank() ? "—" : v;
    }
}
