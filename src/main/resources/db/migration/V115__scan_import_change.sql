-- V115: Per-import change log, enabling rollback of a scan import's effects.
--
-- Only imports run after this migration get logged — there's no way to reconstruct
-- what earlier imports touched, so rollback is unavailable for historical scan_import
-- rows with no matching change entries.
SET search_path TO ares, public;

CREATE TABLE scan_import_change (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    scan_import_id BIGINT      NOT NULL REFERENCES scan_import(id) ON DELETE CASCADE,
    -- 'asset' | 'detection'
    entity_type    VARCHAR(20) NOT NULL,
    entity_id      BIGINT      NOT NULL,
    -- 'created' | 'updated'
    action         VARCHAR(10) NOT NULL,
    -- Snapshot of the fields this import is about to overwrite, captured immediately
    -- before the mutation — null for 'created' rows (nothing to revert to but deletion).
    prev_values    JSONB,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- Set once this specific change has been reverted/deleted by a rollback run.
    -- Left NULL (not a whole-import status) so a partial rollback can be retried later
    -- for whatever rows were blocked the first time.
    reverted_at    TIMESTAMPTZ
);

CREATE INDEX ix_scan_import_change_import ON scan_import_change(scan_import_id);
CREATE INDEX ix_scan_import_change_entity ON scan_import_change(entity_type, entity_id);
