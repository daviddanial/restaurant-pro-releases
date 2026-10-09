package com.telerad.auth;

import com.telerad.gateway.GatewayAuthentication;
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
        }
        filterChain.doFilter(request, response);
    }
}
