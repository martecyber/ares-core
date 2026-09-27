SET search_path TO ares, public;

-- Track whether a scope entry was added manually or imported from an external platform
ALTER TABLE engagement_scope_entry
    ADD COLUMN source      VARCHAR(50)  NOT NULL DEFAULT 'manual',
    ADD COLUMN external_id VARCHAR(255);

-- Unique index to allow idempotent upserts by (engagement, platform source, external id)
CREATE UNIQUE INDEX uq_scope_entry_external
    ON engagement_scope_entry (engagement_id, source, external_id)
    WHERE external_id IS NOT NULL;
