-- Generalizes the "no year in the project code, continuous per-org sequence" numbering that
-- MONITOR's root type already gets today (hardcoded by literal code check in
-- ProjectService.generateProjectCode) into a data-driven flag any project type — core-owned or
-- plugin-provided — can opt into. Previously ProjectService also hardcoded this for the
-- bughunting plugin's "BH" type specifically; that hardcoding is being replaced by this column,
-- set via the new ProjectTypeFacade when the bughunting plugin installs.
ALTER TABLE ares.project_type ADD COLUMN continuous_numbering boolean NOT NULL DEFAULT false;

UPDATE ares.project_type SET continuous_numbering = true WHERE code = 'MONITOR';
