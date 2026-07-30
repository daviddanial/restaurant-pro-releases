package com.telerad.report;

import com.telerad.common.DeliveryChannel;
import com.telerad.common.DeliveryStatus;
import com.telerad.config.TeleradProperties;
import com.telerad.study.Study;
import com.telerad.tenant.Tenant;
import com.telerad.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.time.Instant;

/**
 * Outbound report delivery beyond portal storage: FHIR webhook POST and email stub.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReportDeliveryService {
    private final ReportDeliveryRepository deliveryRepository;
    private final TenantRepository tenantRepository;
    private final TeleradProperties properties;
    private final RestClient.Builder restClientBuilder;

    public ReportDelivery deliverPortal(Report report, String pdfKey) {
        return save(ReportDelivery.builder()
                .reportId(report.getId())
                .channel(DeliveryChannel.PORTAL)
                .status(DeliveryStatus.DELIVERED)
                .payloadRef(pdfKey)
                .deliveredAt(Instant.now())
                .build());
    }

    public ReportDelivery deliverFhirStored(Report report, String fhirKey) {
        return save(ReportDelivery.builder()
                .reportId(report.getId())
                .channel(DeliveryChannel.FHIR)
                .status(DeliveryStatus.DELIVERED)
                .payloadRef(fhirKey)
                .deliveredAt(Instant.now())
                .build());
    }

    public ReportDelivery deliverFhirWebhook(Study study, Report report, String fhirJson) {
        Tenant tenant = tenantRepository.findById(study.getTenantId()).orElse(null);
        String url = resolveWebhook(tenant);
        ReportDelivery delivery = ReportDelivery.builder()
                .reportId(report.getId())
                .channel(DeliveryChannel.WEBHOOK)
                .status(DeliveryStatus.PENDING)
                .payloadRef(url)
                .build();
        if (url == null) {
            delivery.setStatus(DeliveryStatus.FAILED);
            delivery.setErrorMessage("No hospital webhook URL configured");
            return save(delivery);
        }
        try {
            restClientBuilder.build()
                    .post()
                    .uri(url)
                    .contentType(MediaType.parseMediaType("application/fhir+json"))
                    .header("X-Telerad-Event", "DiagnosticReport.final")
                    .header("X-Telerad-Study-Id", study.getId().toString())
                    .body(fhirJson)
                    .retrieve()
                    .toBodilessEntity();
            delivery.setStatus(DeliveryStatus.DELIVERED);
            delivery.setDeliveredAt(Instant.now());
            log.info("FHIR webhook delivered report {} to {}", report.getId(), url);
        } catch (Exception e) {
            delivery.setStatus(DeliveryStatus.FAILED);
            delivery.setErrorMessage(e.getMessage());
            log.warn("FHIR webhook delivery failed for report {}: {}", report.getId(), e.getMessage());
        }
        return save(delivery);
    }

    public ReportDelivery deliverEmailStub(Study study, Report report, String pdfKey) {
        Tenant tenant = tenantRepository.findById(study.getTenantId()).orElse(null);
        String to = tenant != null && tenant.getNotifyEmail() != null
                ? tenant.getNotifyEmail()
                : properties.getNotify().getFallbackEmail();
        log.info("[DELIVERY:EMAIL] report={} study={} to={} pdf={}", report.getId(), study.getId(), to, pdfKey);
        return save(ReportDelivery.builder()
                .reportId(report.getId())
                .channel(DeliveryChannel.EMAIL)
                .status(DeliveryStatus.DELIVERED)
                .payloadRef("mailto:" + to + ";pdf=" + pdfKey)
                .deliveredAt(Instant.now())
                .build());
    }

    private String resolveWebhook(Tenant tenant) {
        if (tenant != null && tenant.getWebhookUrl() != null && !tenant.getWebhookUrl().isBlank()) {
            return tenant.getWebhookUrl();
        }
        String fallback = properties.getNotify().getDefaultWebhookUrl();
        return fallback == null || fallback.isBlank() ? null : fallback;
    }

    private ReportDelivery save(ReportDelivery delivery) {
        return deliveryRepository.save(delivery);
    }
}
