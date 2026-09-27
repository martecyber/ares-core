-- Fine-grained permission gating SSVC methodology create/update/delete (viewing
-- stays open to any MSSP user via the existing hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')
-- check). Granted to MSSP_ADMIN by default; an admin can extend it to other roles
-- later via the existing Roles & Permissions screen.
INSERT INTO ares.permission (code, description) VALUES
    ('SSVC_WRITE', 'Create, edit, and delete SSVC methodologies')
ON CONFLICT (code) DO NOTHING;

INSERT INTO ares.roles_permission (role_id, permission_id)
SELECT r.id, p.id
FROM ares.role r, ares.permission p
WHERE r.code = 'MSSP_ADMIN' AND p.code = 'SSVC_WRITE'
ON CONFLICT DO NOTHING;
