-- V7: seed the integration service user (Feya EHR calls TeleRad with the
-- service token; audit/feedback columns reference this row).
INSERT INTO users (id, email, password_hash, full_name, role, tenant_id, active, created_at)
VALUES ('00000000-0000-0000-0000-000000000001', 'service@telerad.local',
        '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy',
        'Integration Service', 'ADMIN', NULL, TRUE, NOW())
ON CONFLICT (id) DO NOTHING;
