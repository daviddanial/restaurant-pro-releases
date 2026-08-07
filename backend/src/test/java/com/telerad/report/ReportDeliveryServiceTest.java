package com.telerad.report;

import com.telerad.common.DeliveryChannel;
import com.telerad.common.DeliveryStatus;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReportDeliveryServiceTest {
    @Mock ReportDeliveryRepository deliveryRepository;
    @Mock TenantRepository tenantRepository;
    @Mock RestClient.Builder restClientBuilder;
    @Mock RestClient restClient;
    @Mock RestClient.RequestBodyUriSpec uriSpec;
    @Mock RestClient.RequestBodySpec bodySpec;
    @Mock RestClient.ResponseSpec responseSpec;

    private TeleradProperties properties;
    private ReportDeliveryService service;
    private Report report;
    private Study study;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        properties = new TeleradProperties();
        properties.getNotify().setFallbackEmail("ops@telerad.local");
        properties.getNotify().setDefaultWebhookUrl("");
        service = new ReportDeliveryService(deliveryRepository, tenantRepository, properties, restClientBuilder);

        tenantId = UUID.randomUUID();
        report = Report.builder().id(UUID.randomUUID()).studyId(UUID.randomUUID()).build();
        study = Study.builder().id(UUID.randomUUID()).tenantId(tenantId).studyInstanceUid("1.2.3").build();

        when(deliveryRepository.save(any(ReportDelivery.class))).thenAnswer(inv -> {
            ReportDelivery d = inv.getArgument(0);
            if (d.getId() == null) d.setId(UUID.randomUUID());
            return d;
        });
    }

    @Test
    void deliverPortal_marksDelivered() {
        ReportDelivery d = service.deliverPortal(report, "key/pdf");
        assertThat(d.getChannel()).isEqualTo(DeliveryChannel.PORTAL);
        assertThat(d.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(d.getPayloadRef()).isEqualTo("key/pdf");
        assertThat(d.getDeliveredAt()).isNotNull();
    }

    @Test
    void deliverFhirStored_marksDelivered() {
        ReportDelivery d = service.deliverFhirStored(report, "key/fhir.json");
        assertThat(d.getChannel()).isEqualTo(DeliveryChannel.FHIR);
        assertThat(d.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
    }

    @Test
    void deliverFhirWebhook_failsWhenNoUrl() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());
        ReportDelivery d = service.deliverFhirWebhook(study, report, "{}");
        assertThat(d.getChannel()).isEqualTo(DeliveryChannel.WEBHOOK);
        assertThat(d.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(d.getErrorMessage()).contains("No hospital webhook");
        verify(restClientBuilder, never()).build();
    }

    @Test
    void deliverFhirWebhook_usesTenantUrl() {
        Tenant tenant = Tenant.builder().id(tenantId).code("D").name("D")
                .webhookUrl("https://hospital.example/fhir").build();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        stubRestClientSuccess();

        ReportDelivery d = service.deliverFhirWebhook(study, report, "{\"resourceType\":\"DiagnosticReport\"}");

        assertThat(d.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(d.getPayloadRef()).isEqualTo("https://hospital.example/fhir");
        verify(uriSpec).uri("https://hospital.example/fhir");
    }

    @Test
    void deliverFhirWebhook_usesDefaultWebhookFallback() {
        properties.getNotify().setDefaultWebhookUrl("https://platform.example/hook");
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());
        stubRestClientSuccess();

        ReportDelivery d = service.deliverFhirWebhook(study, report, "{}");
        assertThat(d.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(d.getPayloadRef()).isEqualTo("https://platform.example/hook");
    }

    @Test
    void deliverFhirWebhook_recordsFailureOnHttpError() {
        Tenant tenant = Tenant.builder().id(tenantId).code("D").name("D")
                .webhookUrl("https://hospital.example/fhir").build();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        when(restClientBuilder.build()).thenReturn(restClient);
        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(any())).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.body(anyString())).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenThrow(new RuntimeException("connection refused"));

        ReportDelivery d = service.deliverFhirWebhook(study, report, "{}");
        assertThat(d.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(d.getErrorMessage()).contains("connection refused");
    }

    @Test
    void deliverEmailStub_usesTenantEmail() {
        Tenant tenant = Tenant.builder().id(tenantId).code("D").name("D")
                .notifyEmail("staff@hospital.local").build();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        ReportDelivery d = service.deliverEmailStub(study, report, "pdf-key");
        assertThat(d.getChannel()).isEqualTo(DeliveryChannel.EMAIL);
        assertThat(d.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(d.getPayloadRef()).contains("staff@hospital.local").contains("pdf-key");
    }

    @Test
    void deliverEmailStub_usesFallbackEmail() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());
        ReportDelivery d = service.deliverEmailStub(study, report, "pdf");
        assertThat(d.getPayloadRef()).contains("ops@telerad.local");
    }

    private void stubRestClientSuccess() {
        when(restClientBuilder.build()).thenReturn(restClient);
        when(restClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(any())).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.body(anyString())).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(ResponseEntity.status(HttpStatus.OK).build());
    }
}
