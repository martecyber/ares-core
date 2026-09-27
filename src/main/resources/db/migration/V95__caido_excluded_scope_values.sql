-- Allow each Caido task to specify scope entries that should be skipped during PULL_SCOPE.
ALTER TABLE ares.caido_api_task ADD COLUMN IF NOT EXISTS excluded_scope_values jsonb;
ALTER TABLE ares.caido_task      ADD COLUMN IF NOT EXISTS excluded_scope_values jsonb;
