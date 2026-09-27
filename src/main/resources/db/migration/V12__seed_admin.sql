SET search_path TO ares, public;

-- Default admin user (password: admin123!)
INSERT INTO "user" (email, display_name, password_hash, status, mfa_enforced)
VALUES (
    'admin@example.com',
    'Admin',
    '$2b$10$kG6oDoO6fzSelW.XGgDCEOev0YEffZoDPAOClO0LD6zeCpaWROdAa'::bytea,
    'active',
    FALSE
);

-- Assign MSSP_ADMIN role (organization_id = 0 = platform-wide, no org scope)
INSERT INTO user_role (user_id, role_id, organization_id)
VALUES (
    (SELECT id FROM "user"  WHERE email = 'admin@example.com'),
    (SELECT id FROM role WHERE code  = 'MSSP_ADMIN'),
    0
);
