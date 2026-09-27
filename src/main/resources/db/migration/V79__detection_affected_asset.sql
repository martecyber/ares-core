-- Per-detection list of "affected" assets — assets that the operator considers
-- impacted by a detection, separate from the single asset where the scanner
-- actually saw the finding (detection.asset_id, kept as-is for compatibility).
--
-- Default behaviour: on import, each new detection is created with one row in
-- this table where asset_id = detection.asset_id, so the new dimension acts as
-- a no-op until an operator decides to widen the impact. Existing detections
-- are backfilled the same way.
CREATE TABLE ares.detection_affected_asset (
    detection_id BIGINT NOT NULL REFERENCES ares.detection(id) ON DELETE CASCADE,
    asset_id     BIGINT NOT NULL REFERENCES ares.asset(id)     ON DELETE CASCADE,
    PRIMARY KEY (detection_id, asset_id)
);

CREATE INDEX idx_detection_affected_asset_asset
    ON ares.detection_affected_asset (asset_id);

-- Backfill: every existing detection that has an asset_id gets that same asset
-- as its first affected asset. Detections with asset_id IS NULL are left empty
-- — there's nothing reasonable to default to.
INSERT INTO ares.detection_affected_asset (detection_id, asset_id)
SELECT id, asset_id
FROM ares.detection
WHERE asset_id IS NOT NULL
ON CONFLICT DO NOTHING;
