package com.telerad.gateway;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/gateways")
@RequiredArgsConstructor
public class GatewayAdminController {
    private final GatewayService gatewayService;

    public record CreateGatewayRequest(
            UUID tenantId,
            String name,
            String location,
            String gatewayRole
    ) {}

    public record SetActiveRequest(boolean active) {}

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','HOSPITAL_USER')")
    public List<GatewayService.GatewayDto> list(@RequestParam(required = false) UUID tenantId) {
        return gatewayService.list(tenantId);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public GatewayService.CreateGatewayResult create(@RequestBody CreateGatewayRequest body) {
        return gatewayService.create(body.tenantId(), body.name(), body.location(), body.gatewayRole());
    }

    @PostMapping("/{id}/active")
    @PreAuthorize("hasAnyRole('ADMIN','HOSPITAL_USER')")
    public GatewayService.GatewayDto setActive(@PathVariable UUID id, @RequestBody SetActiveRequest body) {
        return gatewayService.setActive(id, body.active());
    }
}
