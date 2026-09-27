-- Agents and pools become platform-level inventory (no owning organization).
-- A single agent / pool can be granted to projects across multiple orgs via agent_pool_grant.
-- The grant table already carries the (organization_id, project_id) pair — no change there.

-- ── Agent ──────────────────────────────────────────────────────────────────
ALTER TABLE ares.agent           DROP CONSTRAINT uq_agent_org_name;
DROP INDEX IF EXISTS ares.idx_agent_org;
ALTER TABLE ares.agent           DROP COLUMN organization_id;
ALTER TABLE ares.agent           ADD  CONSTRAINT uq_agent_name UNIQUE (name);

-- ── Pool ───────────────────────────────────────────────────────────────────
ALTER TABLE ares.agent_pool      DROP CONSTRAINT uq_agent_pool_org_name;
DROP INDEX IF EXISTS ares.idx_agent_pool_org;
ALTER TABLE ares.agent_pool      DROP COLUMN organization_id;
ALTER TABLE ares.agent_pool      ADD  CONSTRAINT uq_agent_pool_name UNIQUE (name);
