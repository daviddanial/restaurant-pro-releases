CREATE TABLE gateways (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    name VARCHAR(128) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    access_token_hash VARCHAR(100),
    cert_fingerprint VARCHAR(128),
    enrolled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    disabled_at TIMESTAMPTZ
);

CREATE INDEX idx_gateways_tenant ON gateways(tenant_id);

CREATE TABLE gateway_enrollment_tokens (
    id UUID PRIMARY KEY,
    gateway_id UUID NOT NULL REFERENCES gateways(id) ON DELETE CASCADE,
    token_hash VARCHAR(100) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_gateway_enroll_gateway ON gateway_enrollment_tokens(gateway_id);
