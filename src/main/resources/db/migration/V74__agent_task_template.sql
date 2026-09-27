-- Reusable agent-task configurations. Stored in the KB so operators can apply
-- the same tool + args setup across projects without rebuilding the form.
-- Scheduling fields (cron, scheduledFor, startAt) and runtime context
-- (poolId, priority, agentId, projectId) are intentionally NOT in the template;
-- those get filled at task-create time.

CREATE TABLE ares.agent_task_template (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(120) NOT NULL,
    description TEXT,
    tool        VARCHAR(40)  NOT NULL,
    format      VARCHAR(30)  NOT NULL DEFAULT 'default',
    args        JSONB        NOT NULL DEFAULT '{}',
    nac_profile VARCHAR(64),
    creator_id  BIGINT REFERENCES "user"(id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_agent_task_template_tool ON ares.agent_task_template(tool);
