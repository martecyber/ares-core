-- V107: store the GVM task selected when granting a Greenbone/OpenVAS integration.
-- One grant = one task; a project needing results from multiple tasks gets multiple grants.
ALTER TABLE ares.integration_grant ADD COLUMN IF NOT EXISTS task_id   TEXT;
ALTER TABLE ares.integration_grant ADD COLUMN IF NOT EXISTS task_name TEXT;
