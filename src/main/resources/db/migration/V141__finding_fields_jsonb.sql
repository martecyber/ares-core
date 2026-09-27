-- AQL implementation plan, Phase 0: unify Finding's dynamic/custom fields onto a single typed
-- jsonb column, replacing the finding_field EAV table as the live storage for a finding's own
-- field values. This is a hard cutover — application code stops reading/writing finding_field
-- after this release. finding_field and finding_field_type are kept in place, untouched: (a) as
-- an instant rollback path (revert app code, no down-migration needed), and (b) because
-- finding_field_type remains the live catalog for field *types* (createFieldType/etc. are
-- unaffected — templates still hold a real FK to finding_field_type(id), see
-- finding_template_field.type_id in V5__finding.sql, so that catalog can't be retired here).
--
-- Values are keyed by finding_field_type.name (stable, unique) rather than by the old
-- finding_field row's own surrogate id — a finding can only have one field per type (enforced by
-- the app layer already), so the type's name is a sufficient and AQL-friendly key
-- (`fields.impact`, mirroring Asset's `metadata.<key>` convention).

ALTER TABLE ares.finding ADD COLUMN fields JSONB NOT NULL DEFAULT '{}'::jsonb;

-- Seed field_definition (V140) from the existing finding_field_type catalog as a platform-wide
-- (organization_id NULL) snapshot for the AQL field registry (Phase 3) to consume later. This is
-- a one-time copy, not a live sync — finding_field_type remains the catalog createFieldType/
-- updateFieldType/deleteFieldType actually write to; keeping the two in lockstep going forward is
-- out of scope here.
INSERT INTO ares.field_definition (entity_type, field_key, title, description, data_type, is_required, sort_order, is_system, created_at)
SELECT 'finding', name, title, description, 'string', is_required, sort_order, is_system, NOW()
FROM ares.finding_field_type;

-- Backfill existing finding_field rows into finding.fields, keyed by the type's name. Defensive
-- COALESCE/GROUP BY: if a finding ever ended up with more than one row for the same type
-- (shouldn't happen — the app enforces one-per-type — but nothing at the DB level guarantees it
-- historically), jsonb_object_agg silently keeps one of them rather than failing the migration.
UPDATE ares.finding f
SET fields = sub.merged
FROM (
    SELECT ff.finding_id, jsonb_object_agg(fft.name, COALESCE(ff.field_text, '')) AS merged
    FROM ares.finding_field ff
    JOIN ares.finding_field_type fft ON fft.id = ff.type_id
    GROUP BY ff.finding_id
) sub
WHERE f.id = sub.finding_id;
