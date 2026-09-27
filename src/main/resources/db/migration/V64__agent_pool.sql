-- Agent pools group agents so projects subscribe to a pool (not a single machine).
-- Tasks are dispatched against the pool; any pool member can claim them.

CREATE TABLE ares.agent_pool (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT       NOT NULL REFERENCES ares.organization(id) ON DELETE CASCADE,
    name            VARCHAR(120) NOT NULL,
    description     TEXT,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_agent_pool_org_name UNIQUE (organization_id, name)
);
CREATE INDEX idx_agent_pool_org ON ares.agent_pool(organization_id);

CREATE TABLE ares.agent_pool_member (
    pool_id  BIGINT NOT NULL REFERENCES ares.agent_pool(id) ON DELETE CASCADE,
    agent_id BIGINT NOT NULL REFERENCES ares.agent(id)      ON DELETE CASCADE,
    PRIMARY KEY (pool_id, agent_id)
);
CREATE INDEX idx_agent_pool_member_agent ON ares.agent_pool_member(agent_id);

-- Mirrors integration_grant. project_id NULL = org-wide grant (every project in the org can use the pool).
CREATE TABLE ares.agent_pool_grant (
    id              BIGSERIAL PRIMARY KEY,
    agent_pool_id   BIGINT      NOT NULL REFERENCES ares.agent_pool(id) ON DELETE CASCADE,
    organization_id BIGINT      NOT NULL REFERENCES ares.organization(id) ON DELETE CASCADE,
    project_id      BIGINT               REFERENCES ares.project(id) ON DELETE CASCADE,
    active          BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- Two unique constraints depending on whether the grant is org-wide or project-specific
-- (PostgreSQL treats NULL as distinct in UNIQUE, so we split into two partial indexes).
CREATE UNIQUE INDEX uq_pool_grant_org_wide
    ON ares.agent_pool_grant(agent_pool_id, organization_id)
    WHERE project_id IS NULL;
CREATE UNIQUE INDEX uq_pool_grant_project
    ON ares.agent_pool_grant(agent_pool_id, organization_id, project_id)
    WHERE project_id IS NOT NULL;
CREATE INDEX idx_pool_grant_project ON ares.agent_pool_grant(project_id) WHERE project_id IS NOT NULL;
