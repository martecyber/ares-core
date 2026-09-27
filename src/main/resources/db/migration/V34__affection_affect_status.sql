SET search_path TO ares, public;

-- ── affection_asset: add observed_at (detected_at role) and status (affects role) ──

ALTER TABLE ares.affection_asset
    ADD COLUMN IF NOT EXISTS observed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS status      VARCHAR(20);

UPDATE ares.affection_asset SET observed_at = NOW() WHERE role = 'detected_at';
UPDATE ares.affection_asset SET status = 'open'      WHERE role = 'affects';

-- ── affection: add computed status (open / closed) ────────────────────────────────

ALTER TABLE ares.affection
    ADD COLUMN IF NOT EXISTS status VARCHAR(10) NOT NULL DEFAULT 'open';

-- ── affect_status_history: full audit trail for affect status changes ─────────────

CREATE TABLE IF NOT EXISTS ares.affect_status_history (
    id              BIGSERIAL PRIMARY KEY,
    affection_id    BIGINT       NOT NULL REFERENCES ares.affection(id) ON DELETE CASCADE,
    asset_id        BIGINT       NOT NULL,
    from_status     VARCHAR(20),
    to_status       VARCHAR(20)  NOT NULL,
    changed_by      BIGINT,
    changed_by_name VARCHAR(255),
    note            TEXT,
    changed_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX ON ares.affect_status_history(affection_id, asset_id);
CREATE INDEX ON ares.affect_status_history(changed_at DESC);
