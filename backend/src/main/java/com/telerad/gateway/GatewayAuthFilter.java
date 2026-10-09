package com.telerad.gateway;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.cert.X509Certificate;

/**
 * Authenticates edge gateways via mTLS client certificate, or Bearer token when
 * {@code telerad.gateway.bearer-dev=true} (local/dev only).
 */
@Component
@RequiredArgsConstructor
public class GatewayAuthFilter extends OncePerRequestFilter {
    private final GatewayService gatewayService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null) return true;
        if (!path.startsWith("/api/gateway/")) return true;
        return path.equals("/api/gateway/enroll");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof GatewayAuthentication) {
            filterChain.doFilter(request, response);
            return;
        }

        X509Certificate[] certs = (X509Certificate[]) request.getAttribute("jakarta.servlet.request.X509Certificate");
        if (certs != null && certs.length > 0) {
            Gateway g = gatewayService.authenticateClientCert(certs[0]);
            if (g != null) {
                SecurityContextHolder.getContext().setAuthentication(new GatewayAuthentication(g));
                filterChain.doFilter(request, response);
                return;
            }
        }

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = header.substring(7).trim();
            Gateway g = gatewayService.authenticateAccessToken(token);
            if (g != null) {
                SecurityContextHolder.getContext().setAuthentication(new GatewayAuthentication(g));
            }
        }
        filterChain.doFilter(request, response);
    }
}
