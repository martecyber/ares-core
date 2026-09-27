-- Workflows implementation plan, Phase A: core schema for trigger->condition->action graphs.
-- Ships alongside AQL's own Phase 6 hook (an in-memory AqlNode evaluator, built as part of this
-- same phase) which CONDITION nodes depend on. See the plan doc's "Workflows" section for the
-- full architecture. Legacy systems (agent tasks, notifications, sync jobs) are NOT touched by
-- this migration or the phase it belongs to -- Workflows is purely additive for now.

CREATE TABLE ares.workflow (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- 'platform' | 'organization' | 'project'. scope_id has no FK (polymorphic depending on
    -- scope_kind, same convention as messaging_event_binding.scope_id) -- 0 is the platform
    -- sentinel, matching PlatformNotificationService.PLATFORM_SCOPE_ID.
    scope_kind        VARCHAR(20)  NOT NULL,
    scope_id          BIGINT       NOT NULL,
    name              VARCHAR(150) NOT NULL,
    description       TEXT,
    status            VARCHAR(20)  NOT NULL DEFAULT 'draft',  -- draft | active | disabled
    -- { nodes: [...], edges: [...] } -- see WorkflowGraphValidator for the shape.
    graph_definition  JSONB        NOT NULL DEFAULT '{"nodes":[],"edges":[]}',
    version           INT          NOT NULL DEFAULT 1,
    created_by        BIGINT       REFERENCES ares."user"(id) ON DELETE SET NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_workflow_scope ON ares.workflow(scope_kind, scope_id);

CREATE TABLE ares.workflow_trigger (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workflow_id   BIGINT       NOT NULL REFERENCES ares.workflow(id) ON DELETE CASCADE,
    -- References graph_definition's node id -- not an FK, the graph JSON is the source of truth
    -- for node existence; validated by WorkflowGraphValidator, not the database.
    node_id       VARCHAR(64)  NOT NULL,
    trigger_type  VARCHAR(20)  NOT NULL,  -- manual | cron | webhook | event
    config        JSONB        NOT NULL DEFAULT '{}',
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    next_run_at   TIMESTAMPTZ,            -- cron only, mirrors agent_task_schedule.next_run_at
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_workflow_trigger_workflow ON ares.workflow_trigger(workflow_id);
CREATE INDEX ix_workflow_trigger_due ON ares.workflow_trigger(next_run_at)
    WHERE enabled = TRUE AND trigger_type = 'cron';

CREATE TABLE ares.workflow_run (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workflow_id         BIGINT       NOT NULL REFERENCES ares.workflow(id) ON DELETE CASCADE,
    workflow_version    INT          NOT NULL,
    -- Copy of workflow.graph_definition at trigger time -- editing a live workflow must never
    -- retroactively change what an in-flight run executes.
    graph_snapshot       JSONB        NOT NULL,
    trigger_node_id      VARCHAR(64)  NOT NULL,
    triggered_by         VARCHAR(120),  -- user id (manual) or 'cron'/'webhook'/'event'
    status                VARCHAR(20)  NOT NULL DEFAULT 'pending',  -- pending|running|completed|failed|cancelled
    -- Namespaced variable bag: {trigger: {...}, steps: {<nodeId>: {output: {...}}}}.
    context               JSONB        NOT NULL DEFAULT '{}',
    -- Set when this run was spawned by a CALL_WORKFLOW step -- walked for cross-workflow cycle
    -- detection (necessarily a runtime check, unlike the save-time single-workflow DAG check).
    parent_step_run_id   BIGINT,
    started_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    completed_at          TIMESTAMPTZ,
    error                 TEXT
);
CREATE INDEX ix_workflow_run_workflow ON ares.workflow_run(workflow_id);
CREATE INDEX ix_workflow_run_status ON ares.workflow_run(status);

CREATE TABLE ares.workflow_step_run (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workflow_run_id  BIGINT       NOT NULL REFERENCES ares.workflow_run(id) ON DELETE CASCADE,
    node_id          VARCHAR(64)  NOT NULL,
    node_type        VARCHAR(40)  NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'pending',  -- pending|running|waiting|completed|failed|skipped
    input            JSONB,
    output           JSONB,
    error            TEXT,
    -- The async-wait anchor: which legacy-system row this step is waiting on, if any.
    ref_type         VARCHAR(20),  -- AGENT_TASK | JOB | WORKFLOW_RUN
    ref_id           BIGINT,
    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ
);
CREATE INDEX ix_workflow_step_run_run ON ares.workflow_step_run(workflow_run_id);
-- The poller's own scan: WHERE status='waiting', batched by ref_type.
CREATE INDEX ix_workflow_step_run_waiting ON ares.workflow_step_run(status, ref_type)
    WHERE status = 'waiting';

ALTER TABLE ares.workflow_run
    ADD CONSTRAINT fk_workflow_run_parent_step
    FOREIGN KEY (parent_step_run_id) REFERENCES ares.workflow_step_run(id) ON DELETE SET NULL;
