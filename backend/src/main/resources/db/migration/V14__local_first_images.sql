-- Local-first images (KB note 13): the hospital server (edge) keeps the images; the cloud keeps
-- the index of every study and a copy of the image files only while a remote read needs them.

-- An instance without a cloud copy has no object-storage key.
ALTER TABLE instances ALTER COLUMN storage_key DROP NOT NULL;

ALTER TABLE studies
    ADD COLUMN edge_gateway_id    UUID REFERENCES gateways(id),
    -- AVAILABLE: image files are in the cloud. EDGE_ONLY: only on the hospital server.
    -- REQUESTED: the hospital server was asked to upload them.
    -- PENDING_DELETE: retention reached, waiting for the hospital server to confirm it holds the study.
    ADD COLUMN cloud_status       VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE',
    ADD COLUMN cloud_requested_at TIMESTAMPTZ,
    ADD COLUMN cloud_requested_by UUID,
    ADD COLUMN cloud_available_at TIMESTAMPTZ,
    ADD COLUMN cloud_deleted_at   TIMESTAMPTZ,
    -- Set when the hospital server reports it no longer holds the study (cache mode retention).
    ADD COLUMN edge_evicted_at    TIMESTAMPTZ;

CREATE INDEX idx_studies_cloud_status ON studies(cloud_status);
CREATE INDEX idx_studies_edge_gateway ON studies(edge_gateway_id, cloud_status);

-- Per-facility image settings, set by us when the facility is onboarded.
ALTER TABLE tenants
    -- ALL: every study is uploaded to the cloud (previous behaviour).
    -- ON_DEMAND: only the index is sent; image files go up when requested (button or rule).
    ADD COLUMN upload_policy            VARCHAR(16) NOT NULL DEFAULT 'ALL',
    -- JSON list of rules that request a cloud copy automatically, e.g.
    -- [{"modality":"CT","fromHour":18,"toHour":8},{"priority":"STAT"}]
    ADD COLUMN auto_send_rules          TEXT,
    -- Earlier studies of the same patient sent along with a requested study.
    ADD COLUMN priors_to_send           INTEGER NOT NULL DEFAULT 1,
    -- Days after the report is signed before the cloud copy is removed; NULL keeps it.
    ADD COLUMN cloud_retention_days     INTEGER,
    -- ARCHIVE: the hospital server is the archive. CACHE: the facility PACS is the archive.
    ADD COLUMN edge_storage_mode        VARCHAR(16) NOT NULL DEFAULT 'ARCHIVE',
    -- Days the hospital server keeps a study; NULL keeps it for good.
    ADD COLUMN edge_local_retention_days INTEGER;

-- Address browsers on the hospital network use to reach the hospital server's image service.
ALTER TABLE gateways ADD COLUMN lan_url VARCHAR(512);
