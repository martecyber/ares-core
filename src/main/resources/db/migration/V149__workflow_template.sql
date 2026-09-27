-- Reusable workflow graphs. Stored in the KB, platform-wide (no scope column), mirroring
-- agent_task_template/finding_template exactly. graph_definition has the same shape as
-- workflow.graph_definition (nodes+edges) but with scope-bound ids (poolId, integrationId)
-- stripped, so it instantiates cleanly into any org/project without silently carrying over
-- a resource id that may not exist there.

CREATE TABLE ares.workflow_template (
    id               BIGSERIAL PRIMARY KEY,
    name             VARCHAR(120) NOT NULL,
    description      TEXT,
    graph_definition JSONB        NOT NULL,
    creator_id       BIGINT REFERENCES "user"(id) ON DELETE SET NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
