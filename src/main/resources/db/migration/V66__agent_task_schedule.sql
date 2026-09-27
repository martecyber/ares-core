-- Cron schedules that auto-enqueue agent_task rows. Mirrors integration_schedule.

CREATE TABLE ares.agent_task_schedule (
    id              BIGSERIAL PRIMARY KEY,
    project_id      BIGINT       NOT NULL REFERENCES ares.project(id) ON DELETE CASCADE,
    pool_id         BIGINT       NOT NULL REFERENCES ares.agent_pool(id) ON DELETE RESTRICT,
    tool            VARCHAR(40)  NOT NULL,
    format          VARCHAR(30)  NOT NULL DEFAULT 'default',
    args            JSONB        NOT NULL DEFAULT '{}',
    source_ip       VARCHAR(45),
    nac_profile     VARCHAR(64),
    cron_expression VARCHAR(120) NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    last_run_at     TIMESTAMPTZ,
    next_run_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_agent_task_schedule_project ON ares.agent_task_schedule(project_id);
CREATE INDEX idx_agent_task_schedule_due     ON ares.agent_task_schedule(next_run_at) WHERE enabled = TRUE;
