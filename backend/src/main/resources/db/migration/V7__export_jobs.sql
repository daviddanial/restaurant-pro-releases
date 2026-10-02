CREATE TABLE export_jobs (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    study_id UUID NOT NULL REFERENCES studies(id),
    requested_by UUID NOT NULL,
    scope VARCHAR(16) NOT NULL,
    series_instance_uid VARCHAR(128),
    sop_instance_uid VARCHAR(128),
    status VARCHAR(16) NOT NULL,
    total_objects INT NOT NULL DEFAULT 0,
    completed_objects INT NOT NULL DEFAULT 0,
    result_key VARCHAR(512),
    error_message VARCHAR(1024),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at TIMESTAMPTZ
);

CREATE INDEX idx_export_jobs_study ON export_jobs(study_id);
CREATE INDEX idx_export_jobs_requester ON export_jobs(requested_by, created_at DESC);
