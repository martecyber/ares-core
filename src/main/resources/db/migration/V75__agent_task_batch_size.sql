-- Batch splitting for agent task schedules.
-- batch_size = max targets per task when the schedule fires; NULL = no splitting.
-- For one-shot tasks the batchSize is supplied in the API request at creation time
-- and not persisted (the N tasks are created immediately).
ALTER TABLE ares.agent_task_schedule
    ADD COLUMN batch_size INT NULL CHECK (batch_size >= 1);
