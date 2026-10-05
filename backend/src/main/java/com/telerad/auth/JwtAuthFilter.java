package com.telerad.auth;

import com.telerad.common.Role;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {
    private static final UUID SERVICE_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final JwtService jwtService;

    @Value("${telerad.service-token:}")
    private String serviceToken;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // Machine-to-machine auth for integration systems (Feya EHR):
        // X-Telerad-Service-Token must match the configured secret.
        String incoming = request.getHeader("X-Telerad-Service-Token");
        if (incoming != null && !serviceToken.isBlank() && serviceToken.equals(incoming)) {
            UserPrincipal servicePrincipal = new UserPrincipal(
                    SERVICE_USER_ID,
                    "service@telerad.local",
                    "Integration Service",
                    Role.ADMIN,
                    parseUuid(request.getHeader("X-Telerad-Tenant-Id")),
                    request.getHeader("X-Telerad-Tenant-Code")
            );
            var auth = new UsernamePasswordAuthenticationToken(servicePrincipal, null, servicePrincipal.authorities());
            SecurityContextHolder.getContext().setAuthentication(auth);
            filterChain.doFilter(request, response);
            return;
        }

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = jwtService.parse(header.substring(7));
                UUID tenantId = jwtService.tenantId(claims);
                String tenantCode = claims.get("tenantCode", String.class);
                UserPrincipal principal = new UserPrincipal(
                        jwtService.userId(claims),
                        claims.get("email", String.class),
                        claims.get("name", String.class),
                        jwtService.role(claims),
                        tenantId,
                        tenantCode
                );
                var auth = new UsernamePasswordAuthenticationToken(principal, null, principal.authorities());
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
