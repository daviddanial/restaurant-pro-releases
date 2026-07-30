-- Tenants (hospitals)
CREATE TABLE tenants (
    id              UUID PRIMARY KEY,
    code            VARCHAR(64) NOT NULL UNIQUE,
    name            VARCHAR(255) NOT NULL,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE users (
    id              UUID PRIMARY KEY,
    email           VARCHAR(255) NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    full_name       VARCHAR(255) NOT NULL,
    role            VARCHAR(32) NOT NULL,
    tenant_id       UUID REFERENCES tenants(id),
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_tenant ON users(tenant_id);
CREATE INDEX idx_users_role ON users(role);

CREATE TABLE dicom_nodes (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    ae_title        VARCHAR(16) NOT NULL,
    host            VARCHAR(255),
    port            INTEGER,
    direction       VARCHAR(8) NOT NULL,
    allowed_ip      VARCHAR(64),
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (ae_title)
);

CREATE INDEX idx_dicom_nodes_tenant ON dicom_nodes(tenant_id);

CREATE TABLE patients (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenants(id),
    patient_id      VARCHAR(128) NOT NULL,
    patient_name    VARCHAR(255),
    birth_date      DATE,
    sex             VARCHAR(16),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, patient_id)
);

CREATE TABLE studies (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL REFERENCES tenants(id),
    patient_id          UUID REFERENCES patients(id),
    study_instance_uid  VARCHAR(128) NOT NULL,
    accession_number    VARCHAR(64),
    study_description   VARCHAR(512),
    modality            VARCHAR(16),
    study_date          DATE,
    priority            VARCHAR(16) NOT NULL DEFAULT 'ROUTINE',
    status              VARCHAR(32) NOT NULL DEFAULT 'RECEIVED',
    assigned_to         UUID REFERENCES users(id),
    claimed_at          TIMESTAMPTZ,
    ready_at            TIMESTAMPTZ,
    reported_at         TIMESTAMPTZ,
    delivered_at        TIMESTAMPTZ,
    source              VARCHAR(16) NOT NULL DEFAULT 'UPLOAD',
    instance_count      INTEGER NOT NULL DEFAULT 0,
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, study_instance_uid)
);

CREATE INDEX idx_studies_status ON studies(status);
CREATE INDEX idx_studies_tenant_status ON studies(tenant_id, status);
CREATE INDEX idx_studies_assigned ON studies(assigned_to);
CREATE INDEX idx_studies_priority ON studies(priority);

CREATE TABLE series (
    id                  UUID PRIMARY KEY,
    study_id            UUID NOT NULL REFERENCES studies(id) ON DELETE CASCADE,
    series_instance_uid VARCHAR(128) NOT NULL,
    modality            VARCHAR(16),
    series_number       INTEGER,
    series_description  VARCHAR(512),
    instance_count      INTEGER NOT NULL DEFAULT 0,
    UNIQUE (study_id, series_instance_uid)
);

CREATE TABLE instances (
    id                  UUID PRIMARY KEY,
    series_id           UUID NOT NULL REFERENCES series(id) ON DELETE CASCADE,
    sop_instance_uid    VARCHAR(128) NOT NULL,
    sop_class_uid       VARCHAR(128),
    instance_number     INTEGER,
    rows                INTEGER,
    columns             INTEGER,
    number_of_frames    INTEGER NOT NULL DEFAULT 1,
    storage_key         VARCHAR(512) NOT NULL,
    transfer_syntax_uid VARCHAR(128),
    thumbnail_key       VARCHAR(512),
    UNIQUE (series_id, sop_instance_uid)
);

CREATE UNIQUE INDEX idx_instances_sop ON instances(sop_instance_uid);

CREATE TABLE reports (
    id              UUID PRIMARY KEY,
    study_id        UUID NOT NULL UNIQUE REFERENCES studies(id),
    status          VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    findings        TEXT,
    impression      TEXT,
    critical        BOOLEAN NOT NULL DEFAULT FALSE,
    version         INTEGER NOT NULL DEFAULT 1,
    author_id       UUID REFERENCES users(id),
    signer_id       UUID REFERENCES users(id),
    signed_at       TIMESTAMPTZ,
    pdf_storage_key VARCHAR(512),
    optimistic_lock BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE report_deliveries (
    id              UUID PRIMARY KEY,
    report_id       UUID NOT NULL REFERENCES reports(id),
    channel         VARCHAR(16) NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    payload_ref     VARCHAR(512),
    error_message   TEXT,
    delivered_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE audit_events (
    id              UUID PRIMARY KEY,
    tenant_id       UUID,
    user_id         UUID,
    action          VARCHAR(64) NOT NULL,
    entity_type     VARCHAR(64),
    entity_id       VARCHAR(128),
    detail          TEXT,
    ip_address      VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_tenant ON audit_events(tenant_id);
CREATE INDEX idx_audit_created ON audit_events(created_at);
