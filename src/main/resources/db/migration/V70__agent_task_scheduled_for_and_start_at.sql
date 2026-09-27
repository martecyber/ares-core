-- Delayed one-shot tasks + deferred-start recurring schedules.
--
-- agent_task.scheduled_for:
--   NULL                → run as soon as a pool member can claim it (current behavior).
--   future timestamp    → task stays 'pending' but is invisible to claim() until that
--                         instant — gives operators "fire at 22:00 next Tuesday" semantics
--                         without inventing a one-time cron.
--
-- agent_task_schedule.start_at:
--   NULL                → cron starts firing immediately (current behavior).
--   future timestamp    → recurring schedule is "armed" but next_run_at is clamped to
--                         max(cron_next, start_at) so the first fire respects the operator's
--                         requested kickoff moment.

ALTER TABLE ares.agent_task
    ADD COLUMN scheduled_for TIMESTAMPTZ;

-- Replace the claim index so future-scheduled tasks don't pollute the FIFO scan.
DROP INDEX IF EXISTS ares.idx_agent_task_pending;
CREATE INDEX idx_agent_task_pending
    ON ares.agent_task(pool_id, priority DESC, created_at)
    WHERE status = 'pending';
CREATE INDEX idx_agent_task_scheduled_for
    ON ares.agent_task(scheduled_for)
    WHERE status = 'pending' AND scheduled_for IS NOT NULL;

ALTER TABLE ares.agent_task_schedule
    ADD COLUMN start_at TIMESTAMPTZ;
