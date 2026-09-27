-- AQL implementation plan, Phase 0: generalize Detection's status from a free varchar column
-- (validated only by a hardcoded Java map, DetectionService.TRANSITIONS/ESCALATABLE) to a real
-- relational state machine, matching Finding's existing finding_status/finding_status_transition
-- pattern. Statuses and transitions below are a direct, exhaustive transcription of the current
-- DetectionService.TRANSITIONS map.
--
-- ESCALATABLE ({new, reopened} in DetectionService.java) is NOT a transition — it gates a
-- separate action (escalate() creates/links a Finding) rather than moving status from A to B —
-- so it's its own column (detection_status.escalatable), not folded into the transition table.
--
-- Unlike Finding's V141 cutover, `detection.status` (varchar) is NOT frozen/retired here: the
-- string status value is embedded throughout the frontend (CSS classes, badge colors, the CLI,
-- and the updateStatus API's request/response shape) well beyond what's in scope for this
-- migration. It stays the wire format and is kept actively in sync by the app on every write;
-- `status_id` becomes the new source of truth the transition/escalation rules are validated
-- against. Dropping the varchar column is a later migration, once the UI/CLI also move onto the
-- relational model.

CREATE TABLE ares.detection_status (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name         VARCHAR(50) NOT NULL UNIQUE,
    description  TEXT,
    means_closed BOOLEAN NOT NULL DEFAULT FALSE,
    escalatable  BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE ares.detection_status_transition (
    from_status_id BIGINT NOT NULL REFERENCES ares.detection_status(id) ON DELETE CASCADE,
    to_status_id   BIGINT NOT NULL REFERENCES ares.detection_status(id) ON DELETE CASCADE,
    PRIMARY KEY (from_status_id, to_status_id)
);

INSERT INTO ares.detection_status (name, description, means_closed, escalatable) VALUES
    ('new',            'Newly created, pending triage',                    FALSE, TRUE),
    ('escalated',       'Escalated into a finding',                        FALSE, FALSE),
    ('reopened',        'Reopened after being previously closed',          FALSE, TRUE),
    ('solved',          'Fixed and verified',                              TRUE,  FALSE),
    ('false_positive',  'Confirmed not a real issue',                      TRUE,  FALSE),
    ('dismissed',       'Dismissed without further action',                TRUE,  FALSE),
    ('out_of_scope',    'Out of the current engagement scope',             TRUE,  FALSE),
    ('archived',        'Terminal state — no further transitions allowed', TRUE,  FALSE);

INSERT INTO ares.detection_status_transition (from_status_id, to_status_id)
SELECT s1.id, s2.id FROM (VALUES
    ('new', 'escalated'), ('new', 'false_positive'), ('new', 'dismissed'), ('new', 'out_of_scope'),
    ('escalated', 'solved'), ('escalated', 'false_positive'), ('escalated', 'dismissed'), ('escalated', 'out_of_scope'),
    ('reopened', 'escalated'), ('reopened', 'solved'), ('reopened', 'false_positive'), ('reopened', 'dismissed'), ('reopened', 'out_of_scope'),
    ('solved', 'reopened'), ('solved', 'archived'),
    ('false_positive', 'reopened'),
    ('dismissed', 'reopened'),
    ('out_of_scope', 'reopened')
) AS t(from_name, to_name)
JOIN ares.detection_status s1 ON s1.name = t.from_name
JOIN ares.detection_status s2 ON s2.name = t.to_name;

ALTER TABLE ares.detection ADD COLUMN status_id BIGINT REFERENCES ares.detection_status(id);

UPDATE ares.detection d
SET status_id = ds.id
FROM ares.detection_status ds
WHERE ds.name = d.status;

-- Defensive check: detection.status has never been validated against a closed vocabulary at the
-- DB level (DetectionService.create() writes whatever the caller sends), so unlike Finding's
-- status this one isn't provably closed. Fail loudly with the actual offending values rather than
-- either silently dropping rows onto a NULL status_id or letting a bare NOT NULL violation hide
-- what the real problem is.
DO $$
DECLARE
    orphan_count INT;
    orphan_values TEXT;
BEGIN
    SELECT count(*) INTO orphan_count FROM ares.detection WHERE status_id IS NULL;
    IF orphan_count > 0 THEN
        SELECT string_agg(DISTINCT status, ', ') INTO orphan_values FROM ares.detection WHERE status_id IS NULL;
        RAISE EXCEPTION 'V143: % detection row(s) have a status value not present in detection_status: %. Add the missing status name(s) to this migration''s seed (or correct the data) before it can proceed.', orphan_count, orphan_values;
    END IF;
END $$;

ALTER TABLE ares.detection ALTER COLUMN status_id SET NOT NULL;
CREATE INDEX ix_detection_status_id ON ares.detection(status_id);
