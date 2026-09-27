-- Agent task queue. Tasks are created against a pool; any pool member can claim one.
-- Claiming uses FOR UPDATE SKIP LOCKED for safe concurrent dispatch (handled in app code).

CREATE TABLE ares.agent_task (
    id              BIGSERIAL PRIMARY KEY,
    project_id      BIGINT      NOT NULL REFERENCES ares.project(id) ON DELETE CASCADE,
    pool_id         BIGINT      NOT NULL REFERENCES ares.agent_pool(id) ON DELETE RESTRICT,
    agent_id        BIGINT               REFERENCES ares.agent(id) ON DELETE SET NULL,

    -- Tool descriptor — must match an ImportParser.toolId.
    tool            VARCHAR(40) NOT NULL,
    format          VARCHAR(30) NOT NULL DEFAULT 'default',
    args            JSONB       NOT NULL DEFAULT '{}',

    -- Visibility-tracking metadata, propagated to the resulting ScanImport.
    source_ip       VARCHAR(45),
    nac_profile     VARCHAR(64),

    status          VARCHAR(20) NOT NULL,   -- pending | dispatched | running | uploading | completed | failed | cancelled
    priority        INT         NOT NULL DEFAULT 0,
    created_by      BIGINT               REFERENCES "user"(id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    dispatched_at   TIMESTAMPTZ,
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,

    -- Linked schedule (Phase 4) — nullable for one-shot tasks.
    schedule_id     BIGINT,

    -- Set after the result is parsed and ingested.
    import_id       BIGINT               REFERENCES ares.scan_import(id) ON DELETE SET NULL,
    exit_code       INT,
    stderr          TEXT,
    error           TEXT
);

CREATE INDEX idx_agent_task_pending  ON ares.agent_task(pool_id, priority DESC, created_at)
    WHERE status = 'pending';
CREATE INDEX idx_agent_task_agent    ON ares.agent_task(agent_id, status);
CREATE INDEX idx_agent_task_project  ON ares.agent_task(project_id, created_at DESC);
