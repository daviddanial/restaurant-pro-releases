-- V6: physician-order workflow (Feya↔TeleRad)
--   - studies get: suggested radiologist (physician recommendation), external
--     order id (Feya correlation), referring physician, order notes, and
--     nurse feedback fields ("recommended radiologist not available", ...)
--   - users get an availability override (NULL = auto from workload,
--     TRUE/FALSE = manual override)
--   - StudyStatus gains 'ORDERED' (string column, no DDL needed)

ALTER TABLE studies
    ADD COLUMN suggested_radiologist_id UUID REFERENCES users(id),
    ADD COLUMN external_order_id        VARCHAR(128),
    ADD COLUMN referring_physician      VARCHAR(255),
    ADD COLUMN order_notes              VARCHAR(2000),
    ADD COLUMN feedback_message         VARCHAR(2000),
    ADD COLUMN feedback_by              UUID REFERENCES users(id),
    ADD COLUMN feedback_at              TIMESTAMPTZ;

CREATE INDEX idx_studies_external_order ON studies(external_order_id);

ALTER TABLE users ADD COLUMN availability_override BOOLEAN;
