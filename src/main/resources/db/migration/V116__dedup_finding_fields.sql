-- V116: Findings created from a template got a duplicate finding_field row per type —
-- the create-finding form flattens the template's fields into the request body AND the
-- server separately copied the template's fields again, with no dedup between the two.
--
-- Cleans up any duplicates already in production, then adds a constraint so it can't
-- happen again (the application code has also been fixed to not produce them going
-- forward — this is belt-and-suspenders).
SET search_path TO ares, public;

-- Survivor rule per (finding_id, type_id) duplicate group: prefer a row that actually has
-- content over a blank one (the bug typically paired a blank required-field placeholder
-- with the real templated text), then the most recently updated, then the highest id.
DELETE FROM finding_field ff
USING (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY finding_id, type_id
               ORDER BY (field_text IS NOT NULL AND field_text <> '') DESC,
                        updated_at DESC,
                        id DESC
           ) AS rn
    FROM finding_field
) ranked
WHERE ff.id = ranked.id AND ranked.rn > 1;

ALTER TABLE finding_field
    ADD CONSTRAINT uq_finding_field_finding_type UNIQUE (finding_id, type_id);
