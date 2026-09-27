-- Operators can give tasks/schedules a descriptive name. Optional.
ALTER TABLE ares.agent_task          ADD COLUMN name VARCHAR(120);
ALTER TABLE ares.agent_task_schedule ADD COLUMN name VARCHAR(120);
