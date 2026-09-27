-- Extend detection for scanner imports
ALTER TABLE ares.detection
    ALTER COLUMN plugin_id DROP NOT NULL;

ALTER TABLE ares.detection
    DROP CONSTRAINT IF EXISTS detection_engagement_id_asset_id_plugin_id_key;

ALTER TABLE ares.detection
    ADD COLUMN IF NOT EXISTS source_type       VARCHAR(30),
    ADD COLUMN IF NOT EXISTS source_template_id VARCHAR(200),
    ADD COLUMN IF NOT EXISTS dedup_hash        VARCHAR(64),
    ADD COLUMN IF NOT EXISTS occurrence_count  INT NOT NULL DEFAULT 1;

-- Dedup unique constraint: one detection per (engagement, template+asset combo)
CREATE UNIQUE INDEX IF NOT EXISTS idx_detection_dedup
    ON ares.detection (engagement_id, dedup_hash)
    WHERE dedup_hash IS NOT NULL;

-- Keep old plugin-based constraint only for plugin-id rows
CREATE UNIQUE INDEX IF NOT EXISTS idx_detection_plugin
    ON ares.detection (engagement_id, asset_id, plugin_id)
    WHERE plugin_id IS NOT NULL AND dedup_hash IS NULL;

-- Track each import operation
CREATE TABLE IF NOT EXISTS ares.scan_import (
    id                 BIGSERIAL PRIMARY KEY,
    engagement_id      BIGINT NOT NULL REFERENCES ares.engagement(id) ON DELETE CASCADE,
    organization_id    BIGINT NOT NULL REFERENCES ares.organization(id) ON DELETE CASCADE,
    tool               VARCHAR(50) NOT NULL,
    format             VARCHAR(30) NOT NULL DEFAULT 'default',
    status             VARCHAR(20) NOT NULL DEFAULT 'pending',
    filename           VARCHAR(500),
    assets_created     INT NOT NULL DEFAULT 0,
    detections_created INT NOT NULL DEFAULT 0,
    detections_updated INT NOT NULL DEFAULT 0,
    error_message      TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at       TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_scan_import_engagement ON ares.scan_import(engagement_id);
