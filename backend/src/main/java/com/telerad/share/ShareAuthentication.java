package com.telerad.share;

import org.springframework.security.authentication.AbstractAuthenticationToken;

public class ShareAuthentication extends AbstractAuthenticationToken {
    private final SharePrincipal principal;

    public ShareAuthentication(SharePrincipal principal) {
        super(principal.authorities());
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }
}
