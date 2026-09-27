-- Coverage rotation for recurring agent task schedules.
-- max_targets_per_run: when set, only this many targets are sampled per fire
--                      (randomly, from the subset not yet covered this cycle).
-- covered_values:      JSONB array of target strings already visited in the
--                      current rotation cycle; null = cycle not started yet.
ALTER TABLE ares.agent_task_schedule
    ADD COLUMN max_targets_per_run INT  NULL CHECK (max_targets_per_run >= 1),
    ADD COLUMN covered_values      JSONB NULL;
