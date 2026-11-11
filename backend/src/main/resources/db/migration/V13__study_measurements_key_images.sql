-- Measurements drawn in the viewer (length, angle, Cobb, ellipse ROI, density probe) and key images,
-- saved per study so they come back when the study is reopened and can be used in the report.

CREATE TABLE study_measurements (
    id                 UUID PRIMARY KEY,           -- the viewer's annotation UID
    study_id           UUID         NOT NULL REFERENCES studies(id) ON DELETE CASCADE,
    tenant_id          UUID         NOT NULL,
    series_instance_uid VARCHAR(128) NOT NULL,
    sop_instance_uid   VARCHAR(128) NOT NULL,
    frame_number       INT          NOT NULL DEFAULT 1,
    tool_name          VARCHAR(64)  NOT NULL,
    data_json          TEXT         NOT NULL,      -- tool geometry/metadata, opaque to the server
    summary            VARCHAR(500),               -- e.g. "Length 23.4 mm"
    location           VARCHAR(255),               -- e.g. "Liver_NC 2.00, image 45"
    created_by         UUID         NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_study_measurements_study ON study_measurements(study_id);

CREATE TABLE study_key_images (
    id                 UUID PRIMARY KEY,
    study_id           UUID         NOT NULL REFERENCES studies(id) ON DELETE CASCADE,
    tenant_id          UUID         NOT NULL,
    series_instance_uid VARCHAR(128) NOT NULL,
    sop_instance_uid   VARCHAR(128) NOT NULL,
    frame_number       INT          NOT NULL DEFAULT 1,
    window_center      DOUBLE PRECISION,
    window_width       DOUBLE PRECISION,
    location           VARCHAR(255),
    snapshot_storage_key VARCHAR(512),             -- JPEG of the slice as displayed (with measurements)
    created_by         UUID         NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_study_key_image UNIQUE (study_id, sop_instance_uid, frame_number)
);
CREATE INDEX idx_study_key_images_study ON study_key_images(study_id);
