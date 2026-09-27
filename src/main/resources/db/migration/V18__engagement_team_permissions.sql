SET search_path TO ares, public;

INSERT INTO permission (code, description) VALUES
    ('ENGAGEMENT_LEAD',    'Can be assigned as leader of an engagement'),
    ('ENGAGEMENT_OPERATE', 'Can be assigned as operator of an engagement')
ON CONFLICT (code) DO NOTHING;

-- MSSP_ADMIN: can lead and operate engagements
INSERT INTO roles_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r, permission p
WHERE r.code = 'MSSP_ADMIN'
  AND p.code IN ('ENGAGEMENT_LEAD', 'ENGAGEMENT_OPERATE')
ON CONFLICT DO NOTHING;

-- MSSP_OPERATOR: can operate engagements (not lead)
INSERT INTO roles_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r, permission p
WHERE r.code = 'MSSP_OPERATOR'
  AND p.code = 'ENGAGEMENT_OPERATE'
ON CONFLICT DO NOTHING;
