package com.telerad.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telerad.common.StudySource;
import com.telerad.config.SecurityConfig;
import com.telerad.images.EdgeTokenService;
import com.telerad.images.ImageLocationService;
import com.telerad.ingest.DicomObjectIngestService;
import com.telerad.tenant.Tenant;
import com.telerad.tenant.TenantRepository;
import org.springframework.beans.factory.annotation.Value;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@RestController
@RequestMapping("/api/gateway")
@RequiredArgsConstructor
@Slf4j
public class GatewayIngestController {
    private final GatewayService gatewayService;
    private final DicomObjectIngestService dicomObjectIngestService;
    private final GatewayIngestAuditRepository ingestAuditRepository;
    private final ObjectMapper objectMapper;
    private final ImageLocationService imageLocationService;
    private final com.telerad.order.OrderService orderService;
    private final EdgeTokenService edgeTokenService;
    private final TenantRepository tenantRepository;

    @Value("${telerad.cors.origins:}")
    private String webOrigins;

    /** Facility image settings the hospital server follows (KB note 13). */
    public record EdgeConfig(
            String uploadPolicy,
            String storageMode,
            Integer localRetentionDays,
            List<String> webOrigins,
            String passPublicKeyPem
    ) {}

    public record WorkResponse(
            EdgeConfig config,
            List<ImageLocationService.UploadRequest> uploads,
            List<ImageLocationService.VerifyRequest> verify,
            String serverTime
    ) {}

    public record VerifyResult(String studyInstanceUid, int instanceCount) {}

    public record VerifyBody(List<VerifyResult> results) {}

    public record EvictedBody(List<String> studyInstanceUids) {}

    public record EnrollRequest(String enrollmentToken, String csrPem) {}

    public record RenewRequest(String csrPem) {}

    public record InstanceResult(
            String filename,
            String sopInstanceUid,
            String studyInstanceUid,
            String seriesInstanceUid,
            String status,
            String failureClass,
            String reason
    ) {}

    @PostMapping("/enroll")
    public GatewayService.EnrollResult enroll(@RequestBody EnrollRequest body) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body required");
        }
        return gatewayService.enroll(body.enrollmentToken(), body.csrPem());
    }

    @PostMapping("/renew")
    @PreAuthorize("hasRole('GATEWAY')")
    public GatewayService.RenewResult renew(@RequestBody RenewRequest body) {
        GatewayAuthentication auth = currentGateway();
        Gateway gateway = gatewayService.requireActive(auth.getGatewayId());
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body required");
        }
        return gatewayService.renew(gateway, body.csrPem());
    }

    @PostMapping(value = "/ingest", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('GATEWAY')")
    public Map<String, Object> ingest(
            @RequestPart("files") MultipartFile[] files,
            @RequestParam(value = "studyComplete", required = false) Boolean studyComplete,
            HttpServletRequest request
    ) {
        GatewayAuthentication auth = currentGateway();
        Gateway gateway = gatewayService.requireActive(auth.getGatewayId());
        UUID requestId = UUID.randomUUID();
        String sourceIp = clientIp(request);

        List<InstanceResult> results = new ArrayList<>();
        int stored = 0;
        int duplicates = 0;
        int failed = 0;
        Set<String> studyUids = new LinkedHashSet<>();

        for (MultipartFile file : files == null ? new MultipartFile[0] : files) {
            String name = Optional.ofNullable(file.getOriginalFilename()).orElse("instance.dcm");
            try {
                var one = dicomObjectIngestService.ingestBytes(
                        gateway.getTenantId(),
                        file.getBytes(),
                        StudySource.GATEWAY,
                        null,
                        "GATEWAY"
                );
                studyUids.add(one.studyInstanceUid());
                String status = one.duplicate() ? "duplicate" : "stored";
                if (one.duplicate()) {
                    duplicates++;
                } else {
                    stored++;
                }
                results.add(new InstanceResult(name, one.sopInstanceUid(), one.studyInstanceUid(),
                        one.seriesInstanceUid(), status, null, null));
                persistAudit(gateway, requestId, sourceIp, one.studyInstanceUid(), one.seriesInstanceUid(),
                        one.sopInstanceUid(), status, null, null);
            } catch (Exception e) {
                failed++;
                String failureClass = classifyFailure(e);
                log.warn("Gateway ingest failed file={} gw={} class={}: {}",
                        name, gateway.getId(), failureClass, e.getMessage());
                results.add(new InstanceResult(name, null, null, null, "failed", failureClass, e.getMessage()));
                persistAudit(gateway, requestId, sourceIp, null, null, null, "failed", failureClass, e.getMessage());
            }
        }

        if (stored > 0 || duplicates > 0) {
            gatewayService.markSuccessfulUpload(gateway);
            for (String uid : studyUids) {
                imageLocationService.afterUploaded(gateway, gateway.getTenantId(), uid);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requestId", requestId);
        out.put("gatewayId", gateway.getId());
        out.put("tenantId", gateway.getTenantId());
        out.put("stored", stored);
        out.put("duplicates", duplicates);
        out.put("failed", failed);
        out.put("studyComplete", studyComplete);
        out.put("studyInstanceUids", studyUids);
        out.put("results", results);
        return out;
    }

    /**
     * Index of studies kept on the hospital server: header-only DICOM objects (no pixel data).
     * The cloud creates the study/series/instance rows so the worklist shows the study; the image
     * files stay on the hospital server until a cloud copy is requested.
     */
    @PostMapping(value = "/index", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('GATEWAY')")
    public Map<String, Object> index(
            @RequestPart("files") MultipartFile[] files,
            @RequestParam(value = "studyComplete", required = false) Boolean studyComplete,
            HttpServletRequest request
    ) {
        GatewayAuthentication auth = currentGateway();
        Gateway gateway = gatewayService.requireActive(auth.getGatewayId());
        UUID requestId = UUID.randomUUID();
        String sourceIp = clientIp(request);
        List<InstanceResult> results = new ArrayList<>();
        int indexed = 0;
        int failed = 0;
        Set<String> studyUids = new LinkedHashSet<>();
        for (MultipartFile file : files == null ? new MultipartFile[0] : files) {
            String name = Optional.ofNullable(file.getOriginalFilename()).orElse("instance.dcm");
            try {
                var one = dicomObjectIngestService.indexHeader(gateway.getTenantId(), file.getBytes());
                studyUids.add(one.studyInstanceUid());
                indexed++;
                results.add(new InstanceResult(name, one.sopInstanceUid(), one.studyInstanceUid(),
                        one.seriesInstanceUid(), "indexed", null, null));
            } catch (Exception e) {
                failed++;
                String failureClass = classifyFailure(e);
                log.warn("Gateway index failed file={} gw={} class={}: {}",
                        name, gateway.getId(), failureClass, e.getMessage());
                results.add(new InstanceResult(name, null, null, null, "failed", failureClass, e.getMessage()));
                persistAudit(gateway, requestId, sourceIp, null, null, null, "index_failed", failureClass,
                        e.getMessage());
            }
        }
        for (String uid : studyUids) {
            imageLocationService.afterIndexed(gateway, gateway.getTenantId(), uid, Boolean.TRUE.equals(studyComplete));
            if (Boolean.TRUE.equals(studyComplete)) orderService.onStudyComplete(gateway.getTenantId(), uid);
            persistAudit(gateway, requestId, sourceIp, uid, null, null, "indexed", null, null);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requestId", requestId);
        out.put("indexed", indexed);
        out.put("failed", failed);
        out.put("studyInstanceUids", studyUids);
        out.put("results", results);
        return out;
    }

    /** Polled by the hospital server: its settings, studies to upload, studies to confirm. */
    @GetMapping("/work")
    @PreAuthorize("hasRole('GATEWAY')")
    public WorkResponse work() {
        Gateway gateway = gatewayService.requireActive(currentGateway().getGatewayId());
        Tenant tenant = tenantRepository.findById(gateway.getTenantId()).orElseThrow();
        EdgeConfig config = new EdgeConfig(
                tenant.getUploadPolicy().name(),
                tenant.getEdgeStorageMode().name(),
                tenant.getEdgeLocalRetentionDays(),
                SecurityConfig.allowedOriginPatterns(webOrigins),
                edgeTokenService.publicKeyPem());
        ImageLocationService.Work work = imageLocationService.work(gateway);
        return new WorkResponse(config, work.uploads(), work.verify(), java.time.Instant.now().toString());
    }

    /** The hospital server's count of instances it holds, for studies waiting for cloud removal. */
    @PostMapping("/verify")
    @PreAuthorize("hasRole('GATEWAY')")
    public Map<String, Object> verify(@RequestBody VerifyBody body) {
        Gateway gateway = gatewayService.requireActive(currentGateway().getGatewayId());
        int n = 0;
        for (VerifyResult r : body == null || body.results() == null ? List.<VerifyResult>of() : body.results()) {
            if (r == null || r.studyInstanceUid() == null) continue;
            imageLocationService.confirmHeld(gateway, r.studyInstanceUid(), r.instanceCount());
            n++;
        }
        return Map.of("ok", true, "processed", n);
    }

    /** Studies the hospital server removed (its local retention), or could not find. */
    @PostMapping("/evicted")
    @PreAuthorize("hasRole('GATEWAY')")
    public Map<String, Object> evicted(@RequestBody EvictedBody body) {
        Gateway gateway = gatewayService.requireActive(currentGateway().getGatewayId());
        List<String> uids = body == null || body.studyInstanceUids() == null ? List.of() : body.studyInstanceUids();
        imageLocationService.markEvicted(gateway, uids);
        return Map.of("ok", true, "processed", uids.size());
    }

    @PostMapping("/heartbeat")
    @PreAuthorize("hasRole('GATEWAY')")
    public Map<String, Object> heartbeat(@RequestBody(required = false) Map<String, Object> body) {
        GatewayAuthentication auth = currentGateway();
        Gateway gateway = gatewayService.requireActive(auth.getGatewayId());
        try {
            String json = body == null ? "{}" : objectMapper.writeValueAsString(body);
            gatewayService.recordHeartbeat(gateway, json);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid heartbeat payload");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("serverTime", java.time.Instant.now().toString());
        out.put("certNotAfter", gateway.getCertNotAfter());
        return out;
    }

    private void persistAudit(
            Gateway gateway,
            UUID requestId,
            String sourceIp,
            String studyUid,
            String seriesUid,
            String sopUid,
            String outcome,
            String failureClass,
            String reason
    ) {
        ingestAuditRepository.save(GatewayIngestAudit.builder()
                .gatewayId(gateway.getId())
                .tenantId(gateway.getTenantId())
                .requestId(requestId)
                .studyInstanceUid(studyUid)
                .seriesInstanceUid(seriesUid)
                .sopInstanceUid(sopUid)
                .certSerial(gateway.getCertSerial())
                .certFingerprint(gateway.getCertFingerprint())
                .sourceIp(sourceIp)
                .outcome(outcome)
                .failureClass(failureClass)
                .reason(reason == null ? null : reason.substring(0, Math.min(reason.length(), 1000)))
                .build());
    }

    static String classifyFailure(Exception e) {
        if (e instanceof IllegalArgumentException) return "permanent";
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        if (msg.contains("missing study") || msg.contains("not a dicom") || msg.contains("invalid")) {
            return "permanent";
        }
        return "temporary";
    }

    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private GatewayAuthentication currentGateway() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a instanceof GatewayAuthentication ga) return ga;
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Gateway authentication required");
    }
}
