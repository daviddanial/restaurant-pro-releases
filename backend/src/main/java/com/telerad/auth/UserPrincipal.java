package com.telerad.auth;

import com.telerad.common.Role;
import lombok.Getter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Getter
public class UserPrincipal {
    private final UUID id;
    private final String email;
    private final String fullName;
    private final Role role;
    private final UUID tenantId;
    private final String tenantCode;

    public UserPrincipal(UUID id, String email, String fullName, Role role, UUID tenantId, String tenantCode) {
        this.id = id;
        this.email = email;
        this.fullName = fullName;
        this.role = role;
        this.tenantId = tenantId;
        this.tenantCode = tenantCode;
    }

    public Collection<? extends GrantedAuthority> authorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    public static UserPrincipal current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
            throw new IllegalStateException("No authenticated user");
        }
        return principal;
    }
}
