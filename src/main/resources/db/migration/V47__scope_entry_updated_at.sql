SET search_path TO ares, public;

ALTER TABLE engagement_scope_entry
    ADD COLUMN updated_at TIMESTAMPTZ;

-- Back-fill existing rows with their created_at value
UPDATE engagement_scope_entry SET updated_at = created_at WHERE updated_at IS NULL;
