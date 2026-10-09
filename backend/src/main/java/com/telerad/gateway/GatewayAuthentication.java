package com.telerad.gateway;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.UUID;

public class GatewayAuthentication extends AbstractAuthenticationToken {
    private final UUID gatewayId;
    private final UUID tenantId;
    private final String gatewayName;

    public GatewayAuthentication(Gateway gateway) {
        super(List.of(new SimpleGrantedAuthority("ROLE_GATEWAY")));
        this.gatewayId = gateway.getId();
        this.tenantId = gateway.getTenantId();
        this.gatewayName = gateway.getName();
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public Object getPrincipal() {
        return gatewayId;
    }

    public UUID getGatewayId() {
        return gatewayId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getGatewayName() {
        return gatewayName;
    }
}
