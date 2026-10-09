-- FR-EDGE A1–Q2: gateway labels, mTLS cert fields, heartbeat, ingest audit

ALTER TABLE gateways
    ADD COLUMN IF NOT EXISTS location VARCHAR(256),
    ADD COLUMN IF NOT EXISTS gateway_role VARCHAR(32) NOT NULL DEFAULT 'PRIMARY',
    ADD COLUMN IF NOT EXISTS cert_serial VARCHAR(64),
    ADD COLUMN IF NOT EXISTS cert_not_before TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS cert_not_after TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_heartbeat_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_heartbeat_payload TEXT,
    ADD COLUMN IF NOT EXISTS last_successful_upload_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS revoked_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_gateways_cert_fingerprint ON gateways(cert_fingerprint);
CREATE INDEX IF NOT EXISTS idx_gateways_cert_serial ON gateways(cert_serial);
CREATE INDEX IF NOT EXISTS idx_gateways_last_heartbeat ON gateways(last_heartbeat_at);

CREATE TABLE gateway_ingest_audit (
    id UUID PRIMARY KEY,
    gateway_id UUID NOT NULL REFERENCES gateways(id),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    request_id UUID NOT NULL,
    study_instance_uid VARCHAR(128),
    series_instance_uid VARCHAR(128),
    sop_instance_uid VARCHAR(128),
    cert_serial VARCHAR(64),
    cert_fingerprint VARCHAR(128),
    source_ip VARCHAR(64),
    outcome VARCHAR(32) NOT NULL,
    failure_class VARCHAR(32),
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    legal_hold BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_gw_ingest_audit_gateway ON gateway_ingest_audit(gateway_id, created_at DESC);
CREATE INDEX idx_gw_ingest_audit_request ON gateway_ingest_audit(request_id);
CREATE INDEX idx_gw_ingest_audit_created ON gateway_ingest_audit(created_at);

CREATE TABLE gateway_cert_events (
    id UUID PRIMARY KEY,
    gateway_id UUID NOT NULL REFERENCES gateways(id),
    event_type VARCHAR(32) NOT NULL,
    cert_serial VARCHAR(64),
    cert_fingerprint VARCHAR(128),
    detail TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_gw_cert_events_gateway ON gateway_cert_events(gateway_id, created_at DESC);
