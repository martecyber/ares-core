-- V119: Configurable per-task execution timeout. NULL = no timeout (must be explicitly
-- disabled by the caller — the application layer defaults new tasks/schedules to 60
-- minutes when the field isn't provided; this migration doesn't backfill a default for
-- existing rows since those are unaffected either way once dispatched/completed).
SET search_path TO ares, public;

ALTER TABLE agent_task          ADD COLUMN IF NOT EXISTS timeout_minutes INT;
ALTER TABLE agent_task_template ADD COLUMN IF NOT EXISTS timeout_minutes INT;
ALTER TABLE agent_task_schedule ADD COLUMN IF NOT EXISTS timeout_minutes INT;
