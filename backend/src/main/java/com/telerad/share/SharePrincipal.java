package com.telerad.share;

import lombok.Getter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Getter
public class SharePrincipal {
    private final UUID shareId;
    private final UUID tenantId;
    private final UUID patientId;
    private final int codeVersion;
    private final boolean includeReports;
    private final boolean allowDicomDownload;

    public SharePrincipal(
            UUID shareId,
            UUID tenantId,
            UUID patientId,
            int codeVersion,
            boolean includeReports,
            boolean allowDicomDownload
    ) {
        this.shareId = shareId;
        this.tenantId = tenantId;
        this.patientId = patientId;
        this.codeVersion = codeVersion;
        this.includeReports = includeReports;
        this.allowDicomDownload = allowDicomDownload;
    }

    public Collection<? extends GrantedAuthority> authorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_SHARE"));
    }

    public static SharePrincipal current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof SharePrincipal principal)) {
            throw new IllegalStateException("No share authentication");
        }
        return principal;
    }

    public static SharePrincipal currentOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof SharePrincipal principal) {
            return principal;
        }
        return null;
    }
}
