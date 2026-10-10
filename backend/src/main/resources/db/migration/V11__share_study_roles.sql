-- Study-scoped shares: one MAIN study + frozen PRIOR studies at create time.
ALTER TABLE patient_shares
    ADD COLUMN main_study_id UUID REFERENCES studies(id);

ALTER TABLE patient_share_studies
    ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'MAIN';

UPDATE patient_share_studies SET role = 'MAIN' WHERE role IS NULL OR role = '';

CREATE INDEX idx_patient_shares_main_study ON patient_shares(main_study_id);
CREATE INDEX idx_patient_share_studies_share_role ON patient_share_studies(share_id, role);
