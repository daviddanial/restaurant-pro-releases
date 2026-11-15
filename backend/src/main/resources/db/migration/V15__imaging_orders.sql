-- Orders and scanner workflow (Engineering KB note 15).
-- Off by default per facility (tenants.orders_enabled): existing facilities keep today's behaviour.

ALTER TABLE tenants
    ADD COLUMN orders_enabled      BOOLEAN     NOT NULL DEFAULT FALSE,
    -- AUTO: ready to read when the images stop arriving; WAIT_FOR_MRT: only after "Scan complete"
    ADD COLUMN ready_mode          VARCHAR(16) NOT NULL DEFAULT 'AUTO',
    -- no new image for this long = scan finished
    ADD COLUMN scan_stable_seconds INT         NOT NULL DEFAULT 120,
    -- accession numbers are <prefix>-<yyMMdd>-<nnnn>; null = tenant code
    ADD COLUMN accession_prefix    VARCHAR(16);

-- Demographics typed by reception win over what the scanner writes into the image header.
ALTER TABLE patients ADD COLUMN registered BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE imaging_orders (
    id                    UUID PRIMARY KEY,
    tenant_id             UUID         NOT NULL REFERENCES tenants(id),
    accession_number      VARCHAR(64)  NOT NULL,
    patient_id            UUID         NOT NULL REFERENCES patients(id),
    modality              VARCHAR(16)  NOT NULL,
    procedure_description VARCHAR(255),
    priority              VARCHAR(16)  NOT NULL DEFAULT 'ROUTINE',
    referring_physician   VARCHAR(255),
    clinical_info         TEXT,
    source                VARCHAR(16)  NOT NULL DEFAULT 'MANUAL',
    status                VARCHAR(16)  NOT NULL,
    scheduled_date        DATE         NOT NULL,
    study_id              UUID UNIQUE REFERENCES studies(id),
    arrived_at            TIMESTAMPTZ,
    scanning_at           TIMESTAMPTZ,
    scanned_at            TIMESTAMPTZ,
    ready_at              TIMESTAMPTZ,
    mrt_confirmed_at      TIMESTAMPTZ,
    mrt_confirmed_by      UUID,
    cancelled_at          TIMESTAMPTZ,
    cancel_reason         VARCHAR(255),
    created_by            UUID,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version               BIGINT,
    UNIQUE (tenant_id, accession_number)
);
CREATE INDEX idx_orders_tenant_day ON imaging_orders (tenant_id, scheduled_date);
CREATE INDEX idx_orders_status ON imaging_orders (status);

-- Order history: every status change, late or repeated event, and reconciliation decision.
CREATE TABLE order_events (
    id        UUID PRIMARY KEY,
    order_id  UUID         NOT NULL REFERENCES imaging_orders(id) ON DELETE CASCADE,
    at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    user_id   UUID,
    kind      VARCHAR(32)  NOT NULL,
    detail    VARCHAR(512)
);
CREATE INDEX idx_order_events_order ON order_events (order_id, at);

-- Per-facility, per-day counter for accession numbers (and generated patient IDs on day 1970-01-01).
CREATE TABLE accession_counters (
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    day       DATE NOT NULL,
    value     INT  NOT NULL,
    PRIMARY KEY (tenant_id, day)
);

ALTER TABLE studies
    -- LINKED, NEEDS_CHECK, SUGGESTED, UNMATCHED, RELEASED; null when the facility does not use orders
    ADD COLUMN match_state            VARCHAR(16),
    ADD COLUMN match_note             VARCHAR(512),
    ADD COLUMN suggested_order_id     UUID REFERENCES imaging_orders(id) ON DELETE SET NULL,
    -- what the scanner wrote into the header (kept for audit and the "Needs check" screen)
    ADD COLUMN scanner_patient_id     VARCHAR(128),
    ADD COLUMN scanner_patient_name   VARCHAR(255),
    ADD COLUMN scanner_birth_date     DATE,
    ADD COLUMN name_differs           BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN last_image_at          TIMESTAMPTZ,
    ADD COLUMN images_after_report_at TIMESTAMPTZ;
CREATE INDEX idx_studies_match_state ON studies (tenant_id, match_state);
