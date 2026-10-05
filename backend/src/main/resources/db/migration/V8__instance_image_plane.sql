-- Image plane tags for spatial sync scroll (FR-VW-010).
-- Stored as comma-separated doubles: IPP "x,y,z", IOP "r0,r1,r2,c0,c1,c2".
ALTER TABLE instances
    ADD COLUMN IF NOT EXISTS image_position_patient VARCHAR(128),
    ADD COLUMN IF NOT EXISTS image_orientation_patient VARCHAR(256);
