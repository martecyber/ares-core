-- V110: manual approval gate for MONITOR project iteration advances.
-- active_iteration_label is the officially blessed iteration (stamped on new findings,
-- shown as "current" in the UI); auto_advance_iterations bypasses approval and keeps
-- the previous stateless behavior of always following the calendar.
ALTER TABLE ares.project ADD COLUMN active_iteration_label VARCHAR(10);
ALTER TABLE ares.project ADD COLUMN auto_advance_iterations BOOLEAN NOT NULL DEFAULT FALSE;

-- Backfill: adopt whatever label existing findings were already published under, so
-- upgrading doesn't retroactively demand approval for iterations already in progress.
-- Projects with a cadence but no findings yet are left NULL — resolveActiveLabel()'s
-- bootstrap path picks them up on first read/publish.
UPDATE ares.project p
   SET active_iteration_label = (
     SELECT MAX(f.iteration_label) FROM ares.finding f WHERE f.project_id = p.id
   )
 WHERE p.iteration_cadence IS NOT NULL;
