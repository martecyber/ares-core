SET search_path TO ares, public;

ALTER TABLE engagement_scope_entry
    ADD COLUMN in_scope BOOLEAN NOT NULL DEFAULT TRUE;
