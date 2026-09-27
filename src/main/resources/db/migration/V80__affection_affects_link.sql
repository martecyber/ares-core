-- Explicit link between each detected_at and the affects assets derived from it
-- within an affection. Until now the affection model held two flat lists
-- (detected_at and affects) via affection_asset.role, with no record of which
-- affect "came from" which detected_at. Operators want to model and edit that
-- relationship explicitly — see the per-detected-at affects blocks in the UI.
--
-- The legacy affection_asset rows (role='affects') stay as the *derived* view:
-- AffectionService keeps them in sync as the union of affects across every
-- detected_at link. Code that reads role='affects' (reports, exports, finding
-- detail aggregates) keeps working unchanged.
CREATE TABLE ares.affection_affects_link (
    affection_id        BIGINT NOT NULL REFERENCES ares.affection(id) ON DELETE CASCADE,
    detected_asset_id   BIGINT NOT NULL REFERENCES ares.asset(id)     ON DELETE CASCADE,
    affects_asset_id    BIGINT NOT NULL REFERENCES ares.asset(id)     ON DELETE CASCADE,
    PRIMARY KEY (affection_id, detected_asset_id, affects_asset_id)
);

CREATE INDEX idx_affection_affects_link_detected
    ON ares.affection_affects_link (affection_id, detected_asset_id);

CREATE INDEX idx_affection_affects_link_affect
    ON ares.affection_affects_link (affection_id, affects_asset_id);

-- Backfill: for each affection, link every existing detected_at with every
-- existing affects (Cartesian product). This preserves the conceptual
-- "everything-against-everything" relationship the old model implied.
-- Affections that lack either side contribute zero rows.
INSERT INTO ares.affection_affects_link (affection_id, detected_asset_id, affects_asset_id)
SELECT d.affection_id, d.asset_id, a.asset_id
FROM ares.affection_asset d
JOIN ares.affection_asset a
       ON a.affection_id = d.affection_id
WHERE d.role = 'detected_at'
  AND a.role = 'affects'
ON CONFLICT DO NOTHING;
