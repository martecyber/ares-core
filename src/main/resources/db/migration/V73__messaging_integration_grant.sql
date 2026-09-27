-- Per-integration access grants. An admin defines a Discord/Slack/Telegram/Teams/Webhook
-- integration and then authorizes specific orgs (project_id NULL → all projects in the
-- org) or specific projects. Bindings can only be created against an integration that has
-- an active grant covering the binding's scope. Mirrors ares.agent_pool_grant.
SET search_path TO ares, public;

CREATE TABLE messaging_integration_grant (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    integration_id  BIGINT      NOT NULL REFERENCES messaging_integration(id) ON DELETE CASCADE,
    organization_id BIGINT      NOT NULL REFERENCES organization(id)          ON DELETE CASCADE,
    project_id      BIGINT               REFERENCES project(id)               ON DELETE CASCADE,
    active          BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- PostgreSQL treats NULL as distinct in plain UNIQUE constraints, so split the dedup into
-- two partial unique indexes: one for org-wide grants, one for project-specific grants.
CREATE UNIQUE INDEX uq_msg_grant_org_wide
    ON messaging_integration_grant(integration_id, organization_id)
    WHERE project_id IS NULL;
CREATE UNIQUE INDEX uq_msg_grant_project
    ON messaging_integration_grant(integration_id, organization_id, project_id)
    WHERE project_id IS NOT NULL;

CREATE INDEX idx_msg_grant_integration ON messaging_integration_grant(integration_id);
CREATE INDEX idx_msg_grant_project     ON messaging_integration_grant(project_id) WHERE project_id IS NOT NULL;
CREATE INDEX idx_msg_grant_org         ON messaging_integration_grant(organization_id);
