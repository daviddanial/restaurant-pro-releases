CREATE TABLE patient_shares (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    patient_id UUID NOT NULL REFERENCES patients(id),
    created_by UUID NOT NULL REFERENCES users(id),
    consent_at TIMESTAMPTZ NOT NULL,
    consent_by UUID NOT NULL REFERENCES users(id),
    link_token_lookup VARCHAR(64) NOT NULL,
    access_code_hash VARCHAR(100) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    code_version INT NOT NULL DEFAULT 1,
    include_reports BOOLEAN NOT NULL DEFAULT TRUE,
    allow_dicom_download BOOLEAN NOT NULL DEFAULT FALSE,
    failed_attempts INT NOT NULL DEFAULT 0,
    locked_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_patient_shares_token_lookup ON patient_shares(link_token_lookup);
CREATE INDEX idx_patient_shares_tenant_patient ON patient_shares(tenant_id, patient_id);
CREATE INDEX idx_patient_shares_expires ON patient_shares(expires_at);

CREATE TABLE patient_share_studies (
    share_id UUID NOT NULL REFERENCES patient_shares(id) ON DELETE CASCADE,
    study_id UUID NOT NULL REFERENCES studies(id),
    PRIMARY KEY (share_id, study_id)
);

CREATE INDEX idx_patient_share_studies_study ON patient_share_studies(study_id);
