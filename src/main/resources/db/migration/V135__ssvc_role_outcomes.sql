-- The reusable, named outcome palette a role's tree leaves pick from was previously
-- derived purely from the tree's own leaves (no storage of its own) — an outcome added
-- but not yet assigned to any leaf existed only in the browser tab's memory and was lost
-- on reload. Persisting it directly on the role makes it survive independently of
-- whether any leaf currently references it.
ALTER TABLE ares.ssvc_role ADD COLUMN IF NOT EXISTS outcomes jsonb NOT NULL DEFAULT '[]'::jsonb;
