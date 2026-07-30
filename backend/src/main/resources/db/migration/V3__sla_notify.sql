-- Tenant notification + SLA configuration
ALTER TABLE tenants
    ADD COLUMN webhook_url VARCHAR(1024),
    ADD COLUMN notify_email VARCHAR(255),
    ADD COLUMN sla_stat_minutes INTEGER NOT NULL DEFAULT 60,
    ADD COLUMN sla_urgent_minutes INTEGER NOT NULL DEFAULT 240,
    ADD COLUMN sla_routine_minutes INTEGER NOT NULL DEFAULT 1440;

UPDATE tenants SET
    webhook_url = 'http://host.docker.internal:9999/webhooks/telerad',
    notify_email = 'staff@demohosp.local'
WHERE code = 'DEMOHOSP';

UPDATE tenants SET
    notify_email = 'staff@citymed.local'
WHERE code = 'CITYMED';

-- Study SLA tracking
ALTER TABLE studies
    ADD COLUMN sla_due_at TIMESTAMPTZ,
    ADD COLUMN sla_breached_at TIMESTAMPTZ,
    ADD COLUMN sla_notified BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_studies_sla_due ON studies(sla_due_at) WHERE status IN ('READY', 'IN_PROGRESS');

-- Notification audit / outbox (proves email/webhook stubs actually fired)
CREATE TABLE notification_events (
    id              UUID PRIMARY KEY,
    event_type      VARCHAR(64) NOT NULL,
    study_id        UUID,
    tenant_id       UUID,
    channel         VARCHAR(16) NOT NULL,
    recipient       VARCHAR(1024),
    payload         TEXT,
    status          VARCHAR(16) NOT NULL,
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notification_events_created ON notification_events(created_at DESC);
CREATE INDEX idx_notification_events_study ON notification_events(study_id);
