package com.telerad.tenant;

import com.telerad.audit.AuditEvent;
import com.telerad.audit.AuditEventRepository;
import com.telerad.auth.UserAccount;
import com.telerad.auth.UserAccountRepository;
import com.telerad.common.DicomDirection;
import com.telerad.common.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminController {
    private final TenantRepository tenantRepository;
    private final DicomNodeRepository dicomNodeRepository;
    private final UserAccountRepository userAccountRepository;
    private final AuditEventRepository auditEventRepository;
    private final PasswordEncoder passwordEncoder;

    public record TenantDto(UUID id, String code, String name, boolean active) {}
    public record CreateTenantRequest(String code, String name) {}
    public record DicomNodeDto(UUID id, UUID tenantId, String aeTitle, String host, Integer port,
                               DicomDirection direction, String allowedIp, boolean active) {}
    public record CreateDicomNodeRequest(UUID tenantId, String aeTitle, String host, Integer port,
                                         DicomDirection direction, String allowedIp) {}
    public record UserDto(UUID id, String email, String fullName, Role role, UUID tenantId, boolean active) {}
    public record CreateUserRequest(String email, String password, String fullName, Role role, UUID tenantId) {}

    @GetMapping("/tenants")
    public List<TenantDto> tenants() {
        return tenantRepository.findAll().stream()
                .map(t -> new TenantDto(t.getId(), t.getCode(), t.getName(), t.isActive()))
                .toList();
    }

    @PostMapping("/tenants")
    public TenantDto createTenant(@RequestBody CreateTenantRequest req) {
        Tenant t = Tenant.builder().code(req.code()).name(req.name()).active(true).build();
        t = tenantRepository.save(t);
        return new TenantDto(t.getId(), t.getCode(), t.getName(), t.isActive());
    }

    @GetMapping("/dicom-nodes")
    public List<DicomNodeDto> nodes() {
        return dicomNodeRepository.findAll().stream()
                .map(n -> new DicomNodeDto(n.getId(), n.getTenantId(), n.getAeTitle(), n.getHost(),
                        n.getPort(), n.getDirection(), n.getAllowedIp(), n.isActive()))
                .toList();
    }

    @PostMapping("/dicom-nodes")
    public DicomNodeDto createNode(@RequestBody CreateDicomNodeRequest req) {
        if (!tenantRepository.existsById(req.tenantId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown tenant");
        }
        DicomNode n = DicomNode.builder()
                .tenantId(req.tenantId())
                .aeTitle(req.aeTitle())
                .host(req.host())
                .port(req.port())
                .direction(req.direction() == null ? DicomDirection.IN : req.direction())
                .allowedIp(req.allowedIp())
                .active(true)
                .build();
        n = dicomNodeRepository.save(n);
        return new DicomNodeDto(n.getId(), n.getTenantId(), n.getAeTitle(), n.getHost(), n.getPort(),
                n.getDirection(), n.getAllowedIp(), n.isActive());
    }

    @GetMapping("/users")
    public List<UserDto> users() {
        return userAccountRepository.findAll().stream()
                .map(u -> new UserDto(u.getId(), u.getEmail(), u.getFullName(), u.getRole(),
                        u.getTenant() == null ? null : u.getTenant().getId(), u.isActive()))
                .toList();
    }

    @PostMapping("/users")
    public UserDto createUser(@RequestBody CreateUserRequest req) {
        if (userAccountRepository.findByEmailIgnoreCase(req.email()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email exists");
        }
        Tenant tenant = null;
        if (req.tenantId() != null) {
            tenant = tenantRepository.findById(req.tenantId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown tenant"));
        }
        UserAccount u = UserAccount.builder()
                .email(req.email())
                .passwordHash(passwordEncoder.encode(req.password()))
                .fullName(req.fullName())
                .role(req.role())
                .tenant(tenant)
                .active(true)
                .build();
        u = userAccountRepository.save(u);
        return new UserDto(u.getId(), u.getEmail(), u.getFullName(), u.getRole(),
                tenant == null ? null : tenant.getId(), u.isActive());
    }

    @GetMapping("/audit")
    public List<AuditEvent> audit() {
        return auditEventRepository.findTop100ByOrderByCreatedAtDesc();
    }
}
