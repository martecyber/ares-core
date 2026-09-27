-- V114: Retesting project type.
--
-- RETEST is a root type (like ASSESS): mandatory start/end dates, closes normally —
-- unlike MONITOR (open-ended) and unlike BH (whose subtypes hardcode per-platform
-- behavior). Users may define their own RETEST subtypes via the admin UI; they behave
-- identically to the root (same convention as MONITOR's user-defined subtypes, see V104).
--
-- Findings are never created inside a RETEST project — they keep belonging to their
-- original project. project_retest_finding only records which existing findings are
-- in scope for a given retest; all editing (affected-asset status, notes, detections)
-- happens through the finding's own existing endpoints, unchanged.
SET search_path TO ares, public;

INSERT INTO project_type (name, code, supertype_id, is_system, description) VALUES
    ('Retesting', 'RETEST', NULL, TRUE,
     'Verifies whether findings from a prior assessment are still present. No new findings can be created; existing findings from other projects are linked into scope for review.');

CREATE TABLE project_retest_finding (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    retest_project_id BIGINT      NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    finding_id        BIGINT      NOT NULL REFERENCES finding(id) ON DELETE CASCADE,
    linked_by         BIGINT      REFERENCES "user"(id) ON DELETE SET NULL,
    linked_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (retest_project_id, finding_id)
);

CREATE INDEX ix_retest_finding_project ON project_retest_finding(retest_project_id);
CREATE INDEX ix_retest_finding_finding ON project_retest_finding(finding_id);
