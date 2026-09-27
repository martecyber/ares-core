SET search_path TO ares, public;

-- Helper: insert role_permission by code, skip if already exists
-- MSSP_ADMIN — full platform access
INSERT INTO roles_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r, permission p
WHERE r.code = 'MSSP_ADMIN'
  AND p.code IN (
    'USER_READ', 'USER_WRITE', 'USER_DELETE',
    'ORG_READ', 'ORG_WRITE', 'ORG_DELETE',
    'ENGAGEMENT_READ', 'ENGAGEMENT_WRITE', 'ENGAGEMENT_DELETE',
    'FINDING_READ', 'FINDING_WRITE', 'FINDING_DELETE',
    'ASSET_READ', 'ASSET_WRITE',
    'REPORT_READ', 'REPORT_GENERATE',
    'AUDIT_READ',
    'KB_READ', 'KB_WRITE',
    'INTEGRATION_READ', 'INTEGRATION_WRITE',
    'ROLE_READ', 'ROLE_WRITE',
    'JOB_READ',
    'FILE_READ', 'FILE_WRITE'
  )
ON CONFLICT DO NOTHING;

-- MSSP_OPERATOR — operational access, no admin tasks
INSERT INTO roles_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r, permission p
WHERE r.code = 'MSSP_OPERATOR'
  AND p.code IN (
    'USER_READ',
    'ORG_READ',
    'ENGAGEMENT_READ', 'ENGAGEMENT_WRITE',
    'FINDING_READ', 'FINDING_WRITE',
    'ASSET_READ', 'ASSET_WRITE',
    'REPORT_READ', 'REPORT_GENERATE',
    'KB_READ',
    'INTEGRATION_READ',
    'JOB_READ',
    'FILE_READ', 'FILE_WRITE'
  )
ON CONFLICT DO NOTHING;

-- CLIENT_ADMIN — full access within their organization
INSERT INTO roles_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r, permission p
WHERE r.code = 'CLIENT_ADMIN'
  AND p.code IN (
    'ORG_READ',
    'ENGAGEMENT_READ',
    'FINDING_READ', 'FINDING_WRITE',
    'ASSET_READ',
    'REPORT_READ', 'REPORT_GENERATE',
    'KB_READ',
    'FILE_READ', 'FILE_WRITE'
  )
ON CONFLICT DO NOTHING;

-- CLIENT_USER — read-only within their organization
INSERT INTO roles_permission (role_id, permission_id)
SELECT r.id, p.id
FROM role r, permission p
WHERE r.code = 'CLIENT_USER'
  AND p.code IN (
    'ORG_READ',
    'ENGAGEMENT_READ',
    'FINDING_READ',
    'ASSET_READ',
    'REPORT_READ',
    'KB_READ',
    'FILE_READ'
  )
ON CONFLICT DO NOTHING;
