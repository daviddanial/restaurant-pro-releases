-- Seed demo tenants, users (password for all: password), and AE titles
-- BCrypt hash for "password": $2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3lHK1s8lFqKxYqJzqJzqJzq (will use real one)

INSERT INTO tenants (id, code, name, active) VALUES
  ('11111111-1111-1111-1111-111111111111', 'DEMOHOSP', 'Demo General Hospital', TRUE),
  ('22222222-2222-2222-2222-222222222222', 'CITYMED', 'City Medical Center', TRUE);

-- password = password
INSERT INTO users (id, email, password_hash, full_name, role, tenant_id, active) VALUES
  ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'admin@telerad.local',
   '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
   'Platform Admin', 'ADMIN', NULL, TRUE),
  ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'staff@demohosp.local',
   '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
   'Hospital Staff', 'HOSPITAL_USER', '11111111-1111-1111-1111-111111111111', TRUE),
  ('cccccccc-cccc-cccc-cccc-cccccccccccc', 'rad@telerad.local',
   '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
   'Dr. Jane Radiologist', 'RADIOLOGIST', NULL, TRUE),
  ('dddddddd-dddd-dddd-dddd-dddddddddddd', 'staff@citymed.local',
   '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
   'City Med Staff', 'HOSPITAL_USER', '22222222-2222-2222-2222-222222222222', TRUE);

INSERT INTO dicom_nodes (id, tenant_id, ae_title, host, port, direction, allowed_ip, active) VALUES
  ('eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee', '11111111-1111-1111-1111-111111111111',
   'DEMOHOSP_PACS', '10.0.0.10', 104, 'IN', NULL, TRUE),
  ('ffffffff-ffff-ffff-ffff-ffffffffffff', '22222222-2222-2222-2222-222222222222',
   'CITYMED_PACS', '10.0.0.20', 104, 'IN', NULL, TRUE);
