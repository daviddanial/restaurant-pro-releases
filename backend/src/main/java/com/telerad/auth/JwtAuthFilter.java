package com.telerad.auth;

import com.telerad.gateway.GatewayAuthentication;
import com.telerad.share.ShareAuthentication;
import com.telerad.share.SharePrincipal;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
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
    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof GatewayAuthentication) {
            filterChain.doFilter(request, response);
            return;
        }
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7).trim();
            // Gateway tokens are not JWTs; leave them for GatewayAuthFilter.
            if (token.chars().filter(ch -> ch == '.').count() == 2) {
                try {
                    Claims claims = jwtService.parse(token);
                    if (jwtService.isShareToken(claims)) {
                        SharePrincipal principal = new SharePrincipal(
                                UUID.fromString(claims.getSubject()),
                                UUID.fromString(claims.get("tenantId", String.class)),
                                UUID.fromString(claims.get("patientId", String.class)),
                                claims.get("codeVersion", Integer.class) == null
                                        ? 1
                                        : claims.get("codeVersion", Integer.class),
                                Boolean.TRUE.equals(claims.get("includeReports", Boolean.class)),
                                Boolean.TRUE.equals(claims.get("allowDicomDownload", Boolean.class))
                        );
                        SecurityContextHolder.getContext().setAuthentication(new ShareAuthentication(principal));
                    } else {
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
                    }
                } catch (Exception ignored) {
                    SecurityContextHolder.clearContext();
                }
            }
        }
        filterChain.doFilter(request, response);
    }
}
