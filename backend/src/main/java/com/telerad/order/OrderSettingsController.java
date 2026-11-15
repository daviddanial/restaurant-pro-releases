package com.telerad.order;

import com.telerad.audit.AuditService;
import com.telerad.auth.UserPrincipal;
import com.telerad.tenant.Tenant;
import com.telerad.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.UUID;

/** Per-facility order settings, set by a platform admin (KB note 15). */
@RestController
@RequestMapping("/api/admin/tenants/{tenantId}/order-settings")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class OrderSettingsController {
    private final TenantRepository tenantRepository;
    private final AuditService auditService;

    public record OrderSettings(boolean ordersEnabled, ReadyMode readyMode, int scanStableSeconds,
                                /** 1–5 letters or digits; null uses the hospital code. */
                                String accessionPrefix, String effectivePrefix) {}

    @GetMapping
    @Transactional(readOnly = true)
    public OrderSettings get(@PathVariable UUID tenantId) {
        return toDto(require(tenantId));
    }

    @PutMapping
    @Transactional
    public OrderSettings update(@PathVariable UUID tenantId, @RequestBody OrderSettings body) {
        if (body == null) throw bad("body required");
        Tenant t = require(tenantId);
        if (body.scanStableSeconds() < 30 || body.scanStableSeconds() > 3600) {
            throw bad("Scan finished after: 30–3600 seconds without a new image");
        }
        String prefix = body.accessionPrefix() == null || body.accessionPrefix().isBlank() ? null
                : body.accessionPrefix().trim().toUpperCase(Locale.ROOT);
        if (prefix != null && !prefix.matches("[A-Z0-9]{1,5}")) throw bad("Accession prefix: 1–5 letters or digits");
        t.setOrdersEnabled(body.ordersEnabled());
        t.setReadyMode(body.readyMode() == null ? ReadyMode.AUTO : body.readyMode());
        t.setScanStableSeconds(body.scanStableSeconds());
        t.setAccessionPrefix(prefix);
        tenantRepository.save(t);
        OrderSettings saved = toDto(t);
        auditService.record(t.getId(), UserPrincipal.current().getId(), "ORDER_SETTINGS_UPDATE", "TENANT",
                t.getId().toString(), saved.toString(), null);
        return saved;
    }

    private Tenant require(UUID tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Hospital not found"));
    }

    private static OrderSettings toDto(Tenant t) {
        return new OrderSettings(t.isOrdersEnabled(), t.getReadyMode(), t.getScanStableSeconds(),
                t.getAccessionPrefix(), OrderService.prefix(t));
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
