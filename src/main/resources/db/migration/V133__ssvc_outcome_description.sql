-- Optional description shown on hover for a leaf's outcome, mirroring the
-- description already available on decision points (decision_point_help) and their
-- options (help_text) — lets an authored tree explain "why" a path leads here, not
-- just what it's called.
ALTER TABLE ares.ssvc_tree_node ADD COLUMN IF NOT EXISTS outcome_description TEXT;
