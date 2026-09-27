-- Instrumentation for agent task durations + per-(tool,fingerprint) and
-- per-schedule-run statistics. Phase 1: collect data only — the dispatch
-- ordering remains FIFO+priority (changed in a later phase, once we have
-- enough samples to calibrate SJF/aging factors).

-- ── 1. Per-task: actual duration (ms) + args fingerprint ─────────────
-- actual_duration_ms is populated on complete()/fail() as
-- (completed_at - started_at). args_fingerprint is computed at create
-- time by AgentToolCatalog.computeFingerprint(tool, args).
ALTER TABLE ares.agent_task
    ADD COLUMN actual_duration_ms BIGINT       NULL,
    ADD COLUMN args_fingerprint   VARCHAR(128) NULL;

CREATE INDEX idx_agent_task_tool_fingerprint
    ON ares.agent_task (tool, args_fingerprint)
    WHERE actual_duration_ms IS NOT NULL;

-- ── 2. Schedule runs: group the N tasks created by one cron fire ──────
-- A "run" is one execution of a schedule. The schedule may create many
-- tasks (one per batch). The run is closed when every task is terminal.
CREATE TABLE ares.agent_schedule_run (
    id                BIGSERIAL    PRIMARY KEY,
    schedule_id       BIGINT       NOT NULL REFERENCES ares.agent_task_schedule(id) ON DELETE CASCADE,
    started_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    completed_at      TIMESTAMPTZ  NULL,
    task_count        INT          NOT NULL DEFAULT 0,
    completed_count   INT          NOT NULL DEFAULT 0,
    failed_count      INT          NOT NULL DEFAULT 0,
    total_duration_ms BIGINT       NULL
);

CREATE INDEX idx_agent_schedule_run_schedule
    ON ares.agent_schedule_run (schedule_id, started_at DESC);

-- ── 3. Link each task to its run (NULL for one-shot tasks) ────────────
ALTER TABLE ares.agent_task
    ADD COLUMN schedule_run_id BIGINT NULL
        REFERENCES ares.agent_schedule_run(id) ON DELETE SET NULL;

CREATE INDEX idx_agent_task_schedule_run
    ON ares.agent_task (schedule_run_id)
    WHERE schedule_run_id IS NOT NULL;

-- ── 4. Schedule summary cols (running averages) ───────────────────────
-- Populated by AgentScheduleRunService when a run closes. avg is over
-- the last 10 closed runs (recomputed each time a run closes).
ALTER TABLE ares.agent_task_schedule
    ADD COLUMN last_run_duration_ms BIGINT NULL,
    ADD COLUMN avg_run_duration_ms  BIGINT NULL;

-- ── 5. Aggregated per-(tool, fingerprint) stats ───────────────────────
-- avg = total_duration_ms / sample_count. Percentiles (p50/p90) are NOT
-- cached; they're computed on demand via PERCENTILE_CONT over the
-- agent_task table filtered by the idx_agent_task_tool_fingerprint index.
CREATE TABLE ares.tool_execution_stats (
    tool              VARCHAR(64)  NOT NULL,
    fingerprint       VARCHAR(128) NOT NULL,
    sample_count      BIGINT       NOT NULL DEFAULT 0,
    total_duration_ms BIGINT       NOT NULL DEFAULT 0,
    failure_count     BIGINT       NOT NULL DEFAULT 0,
    last_updated      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tool, fingerprint)
);
