SET search_path TO ares, public;

-- ── detection_status_history: full audit trail for detections ─────────────────────
-- event_type distinguishes: 'created' (first seen), 'reseen' (re-detected by a scan/
-- ingest with no status change), 'status_changed' (manual or scanner-driven transition).
-- changed_by/changed_by_name are NULL for scanner/tool-driven events (created via an
-- async import job with no authenticated user in context) and set for manual changes.

CREATE TABLE IF NOT EXISTS ares.detection_status_history (
    id              BIGSERIAL PRIMARY KEY,
    detection_id    BIGINT       NOT NULL REFERENCES ares.detection(id) ON DELETE CASCADE,
    event_type      VARCHAR(20)  NOT NULL,
    from_status     VARCHAR(20),
    to_status       VARCHAR(20),
    changed_by      BIGINT,
    changed_by_name VARCHAR(255),
    note            TEXT,
    changed_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX ON ares.detection_status_history(detection_id);
CREATE INDEX ON ares.detection_status_history(changed_at DESC);
