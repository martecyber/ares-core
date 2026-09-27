-- Reverts the per-(tool,fingerprint) duration-stats half of V78 — the SJF/aging scheduler
-- it was collecting data for was never pursued. The schedule-run tracking half of V78
-- (agent_schedule_run, agent_task.schedule_run_id, agent_task_schedule.*_run_duration_ms)
-- is a separate, still-used feature and is NOT touched here.

DROP TABLE ares.tool_execution_stats;

DROP INDEX ares.idx_agent_task_tool_fingerprint;

ALTER TABLE ares.agent_task
    DROP COLUMN args_fingerprint;
