-- Bugcrowd, YesWeHack and Intigriti clients have been reviewed/fixed against their real
-- APIs (auth headers, scope endpoints) — re-enabling these BH subtypes.
-- NOTE: "engagement_type" was renamed to "project_type" in V56.
UPDATE ares.project_type SET disabled = FALSE WHERE code IN ('BH_BC', 'BH_YWH', 'BH_INTG');
