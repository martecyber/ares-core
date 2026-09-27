-- V104: Enable MONITOR project type and add iteration support.
--
-- MONITOR already exists (created in V14, disabled in V52).
-- No system subtypes are created — users define their own subtypes via admin UI.
-- The iteration cadence is a per-project setting, not encoded in the type code.

-- Re-enable MONITOR
UPDATE ares.project_type SET disabled = FALSE WHERE code = 'MONITOR';

-- Iteration cadence on project (only meaningful for MONITOR projects)
ALTER TABLE ares.project
    ADD COLUMN IF NOT EXISTS iteration_cadence VARCHAR(20)
        CHECK (iteration_cadence IN ('weekly','biweekly','monthly','quarterly','semiannual','annual'));

-- Iteration label stamped on a finding at publish time (e.g. "26-W02", "26-07", "27-Q2")
ALTER TABLE ares.finding
    ADD COLUMN IF NOT EXISTS iteration_label VARCHAR(10);
