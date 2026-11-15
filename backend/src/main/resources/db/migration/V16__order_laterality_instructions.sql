-- Order form: side of the body and instructions for the MRT (KB note 15).
ALTER TABLE imaging_orders
    ADD COLUMN laterality              VARCHAR(16) NOT NULL DEFAULT 'NOT_APPLICABLE',
    ADD COLUMN additional_instructions TEXT;
