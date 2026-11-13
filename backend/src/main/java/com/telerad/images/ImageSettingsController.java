package com.telerad.images;

import com.telerad.audit.AuditService;
import com.telerad.auth.UserPrincipal;
import com.telerad.common.EdgeStorageMode;
import com.telerad.common.UploadPolicy;
import com.telerad.tenant.Tenant;
import com.telerad.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/** Per-facility image settings, set by a platform admin when the facility is onboarded. */
@RestController
@RequestMapping("/api/admin/tenants/{tenantId}/image-settings")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class ImageSettingsController {
    static final int MAX_DAYS = 36500;

    private final TenantRepository tenantRepository;
    private final AuditService auditService;

    public record ImageSettings(
            UploadPolicy uploadPolicy,
            /** JSON rules, e.g. [{"modality":"CT","fromHour":18,"toHour":8}] */
            String autoSendRules,
            int priorsToSend,
            /** Days after the report is signed before the cloud copy is removed; null keeps it. */
            Integer cloudRetentionDays,
            EdgeStorageMode edgeStorageMode,
            /** Days the hospital server keeps a study; null keeps it for good. */
            Integer edgeLocalRetentionDays
    ) {}

    @GetMapping
    @Transactional(readOnly = true)
    public ImageSettings get(@PathVariable UUID tenantId) {
        return toDto(require(tenantId));
    }

    @PutMapping
    @Transactional
    public ImageSettings update(@PathVariable UUID tenantId, @RequestBody ImageSettings body) {
        if (body == null) throw bad("body required");
        Tenant t = require(tenantId);
        if (body.priorsToSend() < 0 || body.priorsToSend() > 5) throw bad("Priors to send: 0–5");
        checkDays(body.cloudRetentionDays(), "Cloud retention");
        checkDays(body.edgeLocalRetentionDays(), "Hospital server retention");
        String rules;
        try {
            rules = AutoSendRules.normalise(body.autoSendRules());
        } catch (IllegalArgumentException e) {
            throw bad(e.getMessage());
        }
        t.setUploadPolicy(body.uploadPolicy() == null ? UploadPolicy.ALL : body.uploadPolicy());
        t.setAutoSendRules(rules);
        t.setPriorsToSend(body.priorsToSend());
        t.setCloudRetentionDays(body.cloudRetentionDays());
        t.setEdgeStorageMode(body.edgeStorageMode() == null ? EdgeStorageMode.ARCHIVE : body.edgeStorageMode());
        t.setEdgeLocalRetentionDays(body.edgeLocalRetentionDays());
        tenantRepository.save(t);
        ImageSettings saved = toDto(t);
        auditService.record(t.getId(), UserPrincipal.current().getId(), "IMAGE_SETTINGS_UPDATE", "TENANT",
                t.getId().toString(), saved.toString(), null);
        return saved;
    }

    private Tenant require(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Hospital not found"));
    }

    private static ImageSettings toDto(Tenant t) {
        return new ImageSettings(t.getUploadPolicy(), t.getAutoSendRules(), t.getPriorsToSend(),
                t.getCloudRetentionDays(), t.getEdgeStorageMode(), t.getEdgeLocalRetentionDays());
    }

    private static void checkDays(Integer days, String what) {
        if (days != null && (days < 0 || days > MAX_DAYS)) throw bad(what + ": 0–" + MAX_DAYS + " days, or empty");
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
