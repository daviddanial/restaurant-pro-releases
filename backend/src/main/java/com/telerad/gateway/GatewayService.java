package com.telerad.gateway;

import com.telerad.audit.AuditService;
import com.telerad.auth.UserPrincipal;
import com.telerad.common.Role;
import com.telerad.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GatewayService {
    private final GatewayRepository gatewayRepository;
    private final GatewayEnrollmentTokenRepository enrollmentTokenRepository;
    private final GatewayCertEventRepository certEventRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final GatewayCaService caService;
    private final GatewayProperties props;
    private final SecureRandom secureRandom = new SecureRandom();

    public record GatewayDto(
            UUID id,
            UUID tenantId,
            String name,
            String location,
            String gatewayRole,
            boolean active,
            boolean enrolled,
            Instant createdAt,
            Instant enrolledAt,
            Instant certNotAfter,
            Instant lastHeartbeatAt,
            Instant lastSuccessfulUploadAt,
            String lanUrl
    ) {}

    public record CreateGatewayResult(GatewayDto gateway, String enrollmentToken, Instant enrollmentExpiresAt) {}

    public record EnrollResult(
            UUID gatewayId,
            UUID tenantId,
            String certificatePem,
            String caCertificatePem,
            String serialHex,
            String fingerprintSha256,
            Instant notBefore,
            Instant notAfter,
            /** Present only when bearer-dev is enabled (local/dev). */
            String accessToken
    ) {}

    public record RenewResult(
            String certificatePem,
            String caCertificatePem,
            String serialHex,
            String fingerprintSha256,
            Instant notBefore,
            Instant notAfter
    ) {}

    @Transactional
    public CreateGatewayResult create(UUID tenantId, String name, String location, String gatewayRole) {
        requirePlatformAdmin();
        if (!tenantRepository.existsById(tenantId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown tenant");
        }
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        String role = normalizeRole(gatewayRole);
        Gateway g = Gateway.builder()
                .tenantId(tenantId)
                .name(name.trim())
                .location(location == null || location.isBlank() ? null : location.trim())
                .gatewayRole(role)
                .active(true)
                .build();
        g = gatewayRepository.save(g);

        String rawToken = randomToken("enroll");
        GatewayEnrollmentToken et = GatewayEnrollmentToken.builder()
                .gatewayId(g.getId())
                .tokenHash(passwordEncoder.encode(rawToken))
                .expiresAt(Instant.now().plus(props.getEnrollmentTokenHours(), ChronoUnit.HOURS))
                .build();
        enrollmentTokenRepository.save(et);

        UserPrincipal admin = UserPrincipal.current();
        auditService.record(tenantId, admin.getId(), "GATEWAY_CREATE", "Gateway", g.getId().toString(),
                g.getName(), null);

        return new CreateGatewayResult(toDto(g), rawToken, et.getExpiresAt());
    }

    @Transactional(readOnly = true)
    public List<GatewayDto> list(UUID tenantId) {
        UserPrincipal user = UserPrincipal.current();
        if (user.getRole() == Role.HOSPITAL_USER) {
            tenantId = user.getTenantId();
        }
        List<Gateway> rows = tenantId == null
                ? gatewayRepository.findAll()
                : gatewayRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
        return rows.stream().map(this::toDto).toList();
    }

    @Transactional
    public GatewayDto setActive(UUID gatewayId, boolean active) {
        Gateway g = gatewayRepository.findById(gatewayId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        UserPrincipal user = UserPrincipal.current();
        if (active) {
            requirePlatformAdmin();
        } else {
            if (user.getRole() == Role.HOSPITAL_USER) {
                if (user.getTenantId() == null || !user.getTenantId().equals(g.getTenantId())) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your facility");
                }
            } else if (user.getRole() != Role.ADMIN) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed");
            }
        }
        g.setActive(active);
        g.setDisabledAt(active ? null : Instant.now());
        gatewayRepository.save(g);
        auditService.record(g.getTenantId(), user.getId(),
                active ? "GATEWAY_ENABLE" : "GATEWAY_DISABLE",
                "Gateway", g.getId().toString(), null, null);
        return toDto(g);
    }

    @Transactional
    public EnrollResult enroll(String enrollmentToken, String csrPem) {
        if (enrollmentToken == null || enrollmentToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "enrollmentToken required");
        }
        if (csrPem == null || csrPem.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "csrPem required");
        }
        List<GatewayEnrollmentToken> candidates = enrollmentTokenRepository.findAll().stream()
                .filter(t -> t.getUsedAt() == null)
                .filter(t -> t.getExpiresAt().isAfter(Instant.now()))
                .toList();
        GatewayEnrollmentToken match = null;
        for (GatewayEnrollmentToken t : candidates) {
            if (passwordEncoder.matches(enrollmentToken, t.getTokenHash())) {
                match = t;
                break;
            }
        }
        if (match == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired enrollment token");
        }
        Gateway g = gatewayRepository.findById(match.getGatewayId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!g.isActive()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Gateway disabled");
        }

        GatewayCaService.IssuedCert issued = caService.signCsr(csrPem, g.getId());
        applyCert(g, issued);
        g.setEnrolledAt(Instant.now());

        String accessToken = null;
        if (props.isBearerDev()) {
            accessToken = randomToken("gw");
            g.setAccessTokenHash(passwordEncoder.encode(accessToken));
        } else {
            g.setAccessTokenHash(null);
        }
        gatewayRepository.save(g);

        match.setUsedAt(Instant.now());
        enrollmentTokenRepository.save(match);

        certEventRepository.save(GatewayCertEvent.builder()
                .gatewayId(g.getId())
                .eventType("ENROLL")
                .certSerial(issued.serialHex())
                .certFingerprint(issued.fingerprintSha256())
                .build());
        auditService.record(g.getTenantId(), null, "GATEWAY_ENROLL", "Gateway", g.getId().toString(),
                issued.serialHex(), null);

        return new EnrollResult(
                g.getId(),
                g.getTenantId(),
                issued.certificatePem(),
                issued.caCertificatePem(),
                issued.serialHex(),
                issued.fingerprintSha256(),
                issued.notBefore(),
                issued.notAfter(),
                accessToken
        );
    }

    @Transactional
    public RenewResult renew(Gateway gateway, String csrPem) {
        if (!gateway.isActive()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Gateway disabled");
        }
        if (gateway.getRevokedAt() != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Gateway cert revoked; re-enroll");
        }
        if (gateway.getCertNotAfter() != null && gateway.getCertNotAfter().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Certificate expired; re-enroll");
        }
        if (csrPem == null || csrPem.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "csrPem required");
        }
        Instant renewFrom = gateway.getCertNotBefore() == null
                ? Instant.now()
                : gateway.getCertNotBefore().plus(props.getRenewAfterDays(), ChronoUnit.DAYS);
        if (Instant.now().isBefore(renewFrom) && gateway.getCertNotAfter() != null
                && gateway.getCertNotAfter().isAfter(Instant.now().plus(30, ChronoUnit.DAYS))) {
            // Allow early renew only inside last 30 days of life (N1: renew from day 60 of 90)
        }
        // N1: auto-renew from day 60 — reject if cert younger than renewAfterDays unless within 30d of expiry
        if (gateway.getCertNotBefore() != null) {
            Instant earliest = gateway.getCertNotBefore().plus(props.getRenewAfterDays(), ChronoUnit.DAYS);
            boolean nearExpiry = gateway.getCertNotAfter() != null
                    && Instant.now().isAfter(gateway.getCertNotAfter().minus(30, ChronoUnit.DAYS));
            if (Instant.now().isBefore(earliest) && !nearExpiry) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Renewal not yet allowed");
            }
        }

        GatewayCaService.IssuedCert issued = caService.signCsr(csrPem, gateway.getId());
        applyCert(gateway, issued);
        gatewayRepository.save(gateway);
        certEventRepository.save(GatewayCertEvent.builder()
                .gatewayId(gateway.getId())
                .eventType("RENEW")
                .certSerial(issued.serialHex())
                .certFingerprint(issued.fingerprintSha256())
                .build());
        auditService.record(gateway.getTenantId(), null, "GATEWAY_CERT_RENEW", "Gateway",
                gateway.getId().toString(), issued.serialHex(), null);
        return new RenewResult(
                issued.certificatePem(),
                issued.caCertificatePem(),
                issued.serialHex(),
                issued.fingerprintSha256(),
                issued.notBefore(),
                issued.notAfter()
        );
    }

    @Transactional(readOnly = true)
    public Gateway authenticateAccessToken(String rawToken) {
        if (!props.isBearerDev()) return null;
        if (rawToken == null || rawToken.isBlank()) return null;
        for (Gateway g : gatewayRepository.findAll()) {
            if (!g.isActive() || g.getAccessTokenHash() == null) continue;
            if (passwordEncoder.matches(rawToken, g.getAccessTokenHash())) {
                return g;
            }
        }
        return null;
    }

    @Transactional(readOnly = true)
    public Gateway authenticateClientCert(X509Certificate cert) {
        if (cert == null) return null;
        try {
            cert.checkValidity();
            cert.verify(caService.caCertificate().getPublicKey());
        } catch (Exception e) {
            return null;
        }
        String fp = GatewayCaService.fingerprintSha256(getEncoded(cert));
        for (Gateway g : gatewayRepository.findAll()) {
            if (!g.isActive() || g.getCertFingerprint() == null) continue;
            if (g.getRevokedAt() != null) continue;
            if (fp.equalsIgnoreCase(g.getCertFingerprint())) {
                if (g.getCertNotAfter() != null && g.getCertNotAfter().isBefore(Instant.now())) {
                    return null;
                }
                return g;
            }
        }
        return null;
    }

    @Transactional(readOnly = true)
    public Gateway requireActive(UUID gatewayId) {
        Gateway g = gatewayRepository.findById(gatewayId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown gateway"));
        if (!g.isActive()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Gateway disabled");
        }
        if (g.getEnrolledAt() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Gateway not enrolled");
        }
        if (g.getRevokedAt() != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Gateway revoked");
        }
        return g;
    }

    @Transactional
    public void recordHeartbeat(Gateway gateway, String payloadJson) {
        gateway.setLastHeartbeatAt(Instant.now());
        gateway.setLastHeartbeatPayload(payloadJson);
        String lanUrl = lanUrlFrom(payloadJson);
        if (lanUrl != null) gateway.setLanUrl(lanUrl);
        gatewayRepository.save(gateway);
    }

    /** The image-service address the agent reports; only plain http(s) origins are accepted. */
    static String lanUrlFrom(String payloadJson) {
        if (payloadJson == null) return null;
        try {
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payloadJson).get("lanUrl");
            if (node == null || !node.isTextual()) return null;
            String url = node.asText().trim();
            if (url.isEmpty()) return null;
            java.net.URI uri = java.net.URI.create(url);
            String scheme = uri.getScheme();
            if (!"https".equals(scheme) && !"http".equals(scheme)) return null;
            if (uri.getHost() == null || uri.getRawQuery() != null || uri.getRawFragment() != null) return null;
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            if (!path.isEmpty() && !path.equals("/")) return null;
            return scheme + "://" + uri.getRawAuthority();
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional
    public void markSuccessfulUpload(Gateway gateway) {
        gateway.setLastSuccessfulUploadAt(Instant.now());
        gatewayRepository.save(gateway);
    }

    @Transactional(readOnly = true)
    public List<Gateway> findHeartbeatMisses(Instant cutoff) {
        return gatewayRepository.findAll().stream()
                .filter(Gateway::isActive)
                .filter(g -> g.getEnrolledAt() != null)
                .filter(g -> g.getLastHeartbeatAt() == null || g.getLastHeartbeatAt().isBefore(cutoff))
                .toList();
    }

    private void applyCert(Gateway g, GatewayCaService.IssuedCert issued) {
        g.setCertFingerprint(issued.fingerprintSha256());
        g.setCertSerial(issued.serialHex());
        g.setCertNotBefore(issued.notBefore());
        g.setCertNotAfter(issued.notAfter());
        g.setRevokedAt(null);
    }

    private GatewayDto toDto(Gateway g) {
        return new GatewayDto(
                g.getId(),
                g.getTenantId(),
                g.getName(),
                g.getLocation(),
                g.getGatewayRole(),
                g.isActive(),
                g.getEnrolledAt() != null,
                g.getCreatedAt(),
                g.getEnrolledAt(),
                g.getCertNotAfter(),
                g.getLastHeartbeatAt(),
                g.getLastSuccessfulUploadAt(),
                g.getLanUrl()
        );
    }

    private static void requirePlatformAdmin() {
        UserPrincipal user = UserPrincipal.current();
        if (user.getRole() != Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Platform admin only");
        }
    }

    private static String normalizeRole(String gatewayRole) {
        if (gatewayRole == null || gatewayRole.isBlank()) return "PRIMARY";
        String r = gatewayRole.trim().toUpperCase();
        if (!r.equals("PRIMARY") && !r.equals("BACKUP")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "gatewayRole must be PRIMARY or BACKUP");
        }
        return r;
    }

    private String randomToken(String prefix) {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return prefix + "_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] getEncoded(X509Certificate cert) {
        try {
            return cert.getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
