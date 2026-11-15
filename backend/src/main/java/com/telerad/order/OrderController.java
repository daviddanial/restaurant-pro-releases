package com.telerad.order;

import com.telerad.auth.UserPrincipal;
import com.telerad.tenant.Tenant;
import com.telerad.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Reception and MRT: orders, the patient slip data, and the "Needs check" list (KB note 15). */
@RestController
@RequestMapping("/api/orders")
@PreAuthorize("hasAnyRole('HOSPITAL_USER','ADMIN')")
@RequiredArgsConstructor
public class OrderController {
    private final OrderService orderService;
    private final TenantRepository tenantRepository;

    public record Reason(String reason) {}

    public record LinkRequest(UUID orderId) {}

    /** What the reception screen needs to know about its facility. */
    public record FacilityOrderSettings(UUID tenantId, String hospitalName, boolean ordersEnabled,
                                        ReadyMode readyMode, int scanStableSeconds) {}

    @GetMapping("/settings")
    @Transactional(readOnly = true)
    public FacilityOrderSettings settings(@RequestParam(required = false) UUID tenantId) {
        UUID tid = OrderService.tenantFor(UserPrincipal.current(), tenantId);
        Tenant t = tenantRepository.findById(tid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Hospital not found"));
        return new FacilityOrderSettings(t.getId(), t.getName(), t.isOrdersEnabled(), t.getReadyMode(),
                t.getScanStableSeconds());
    }

    @GetMapping
    public List<OrderService.OrderDto> list(@RequestParam(required = false) UUID tenantId,
                                            @RequestParam(required = false)
                                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        return orderService.list(tenantId, day);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderService.OrderDto create(@RequestBody OrderService.CreateOrder body) {
        if (body == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "body required");
        return orderService.create(body);
    }

    @GetMapping("/patient")
    public ResponseEntity<OrderService.PatientDto> patient(@RequestParam(required = false) UUID tenantId,
                                                           @RequestParam String mrn) {
        return orderService.findPatient(tenantId, mrn).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/{id}")
    public OrderService.OrderDetail detail(@PathVariable UUID id) {
        return orderService.detail(id);
    }

    @PostMapping("/{id}/arrived")
    public OrderService.OrderDto arrived(@PathVariable UUID id) {
        return orderService.arrived(id);
    }

    @PostMapping("/{id}/start-scan")
    public OrderService.OrderDto startScan(@PathVariable UUID id) {
        return orderService.startScan(id);
    }

    @PostMapping("/{id}/scan-complete")
    public OrderService.OrderDto scanComplete(@PathVariable UUID id) {
        return orderService.scanComplete(id);
    }

    @PostMapping("/{id}/cancel")
    public OrderService.OrderDto cancel(@PathVariable UUID id, @RequestBody(required = false) Reason body) {
        return orderService.cancel(id, body == null ? null : body.reason());
    }

    @PostMapping("/{id}/no-show")
    public OrderService.OrderDto noShow(@PathVariable UUID id) {
        return orderService.noShow(id);
    }

    @GetMapping("/reconcile")
    public List<OrderService.ReconcileItem> reconcile(@RequestParam(required = false) UUID tenantId) {
        return orderService.reconcileList(tenantId);
    }

    @PostMapping("/reconcile/{studyId}/link")
    public OrderService.OrderDto link(@PathVariable UUID studyId, @RequestBody LinkRequest body) {
        if (body == null || body.orderId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "orderId required");
        }
        return orderService.linkByPerson(studyId, body.orderId());
    }

    @PostMapping("/reconcile/{studyId}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@PathVariable UUID studyId) {
        orderService.rejectSuggestion(studyId);
    }

    @PostMapping("/reconcile/{studyId}/release")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable UUID studyId, @RequestBody(required = false) Reason body) {
        orderService.releaseWithoutOrder(studyId, body == null ? null : body.reason());
    }
}
