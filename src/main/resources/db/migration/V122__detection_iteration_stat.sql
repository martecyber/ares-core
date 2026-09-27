SET search_path TO ares, public;

-- ── detection_iteration_stat: per-iteration snapshot of detections entering each
-- monitoring area (open/escalated/closed) ──────────────────────────────────────
-- Written once per (project, detection, iteration_label, area) the first time a
-- detection transitions into that area during that iteration — a detection that
-- opens AND closes within the same iteration gets one row in each area, since the
-- two are tracked independently rather than as a single "final state". Re-entering
-- the same area again in the same iteration is a no-op (unique constraint), so a
-- detection flapping between reopened/solved repeatedly within one iteration still
-- only counts once per area. Severity is captured at the time of the transition —
-- a later severity change doesn't retroactively alter this snapshot.

CREATE TABLE IF NOT EXISTS ares.detection_iteration_stat (
    id              BIGSERIAL PRIMARY KEY,
    project_id      BIGINT       NOT NULL REFERENCES ares.project(id) ON DELETE CASCADE,
    detection_id    BIGINT       NOT NULL REFERENCES ares.detection(id) ON DELETE CASCADE,
    iteration_label VARCHAR(10)  NOT NULL,
    area            VARCHAR(20)  NOT NULL CHECK (area IN ('open', 'escalated', 'closed')),
    severity        VARCHAR(15)  NOT NULL,
    entered_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (project_id, detection_id, iteration_label, area)
);

CREATE INDEX ON ares.detection_iteration_stat(project_id, iteration_label);
