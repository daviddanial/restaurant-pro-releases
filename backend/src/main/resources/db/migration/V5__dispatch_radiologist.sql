-- Second demo radiologist so Dispatch & Triage has a real assignment list.
INSERT INTO users (id, email, password_hash, full_name, role, tenant_id, active) VALUES
  ('99999999-9999-9999-9999-999999999999', 'rad2@telerad.local',
   '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
   'Dr. Samuel Kimathi', 'RADIOLOGIST', NULL, TRUE)
ON CONFLICT (id) DO NOTHING;
