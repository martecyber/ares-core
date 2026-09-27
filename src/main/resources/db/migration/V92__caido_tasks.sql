SET search_path TO ares, public;

-- Reworks Caido onto the same grants + per-project scheduled-task model as the generic tool
-- integrations (Shodan/Tenable). The plugin pulls due tasks and executes them (Ares can't reach
-- Caido), so task execution is pull-based.

-- The old 1:1 project↔caido-project link is replaced by per-task caido_project_id selection.
DROP TABLE IF EXISTS caido_project_link;

-- Which projects may use a Caido integration. Mirrors integration_grant.
CREATE TABLE caido_integration_grant (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    integration_id   BIGINT       NOT NULL REFERENCES ares.caido_integration(id) ON DELETE CASCADE,
    organization_id  BIGINT       NOT NULL,
    -- Null = org-wide; non-null = restricted to one project.
    project_id       BIGINT       REFERENCES ares.project(id) ON DELETE CASCADE,
    -- ["PULL_SCOPE","PUSH_FINDINGS"]
    capabilities     JSONB        NOT NULL DEFAULT '[]'::jsonb,
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_caido_grant_integration ON ares.caido_integration_grant(integration_id);
CREATE INDEX ix_caido_grant_project     ON ares.caido_integration_grant(project_id);

-- A unit of work the plugin polls + executes for a given Caido project.
-- cron_expression NULL = one-shot; non-null = recurring (standard 5-field cron).
CREATE TABLE caido_task (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    integration_id     BIGINT       NOT NULL REFERENCES ares.caido_integration(id) ON DELETE CASCADE,
    project_id         BIGINT       NOT NULL REFERENCES ares.project(id) ON DELETE CASCADE,
    caido_project_id   VARCHAR(255) NOT NULL,
    caido_project_name VARCHAR(255),
    -- PULL_SCOPE | PUSH_FINDINGS
    action             VARCHAR(30)  NOT NULL,
    cron_expression    VARCHAR(120),
    enabled            BOOLEAN      NOT NULL DEFAULT TRUE,
    -- pending | running | completed | failed
    status             VARCHAR(20)  NOT NULL DEFAULT 'pending',
    result             JSONB,
    error              TEXT,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_run_at        TIMESTAMPTZ,
    next_run_at        TIMESTAMPTZ
);
CREATE INDEX ix_caido_task_project     ON ares.caido_task(project_id);
CREATE INDEX ix_caido_task_integration ON ares.caido_task(integration_id);
CREATE INDEX ix_caido_task_caido_proj  ON ares.caido_task(caido_project_id);
