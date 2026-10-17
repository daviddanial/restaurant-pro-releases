-- Pre-compressed (gzip) copy of uncompressed DICOM objects, served with Content-Encoding: gzip.
-- encodings_checked = true once the object's real transfer syntax was read and the gzip copy
-- was made (or skipped because the pixel data is already compressed / does not shrink).
ALTER TABLE instances
    ADD COLUMN gzip_storage_key VARCHAR(512),
    ADD COLUMN encodings_checked BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_instances_encodings_pending ON instances(id) WHERE encodings_checked = FALSE;
