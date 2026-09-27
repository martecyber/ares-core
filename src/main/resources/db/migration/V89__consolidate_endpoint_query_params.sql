-- V89: Consolidate web_endpoint assets that differ only in query string.
--
-- Step 1: Strip query strings from all web_endpoint identifiers in bulk.
-- Step 2: Build dedup map (window function, no correlated subqueries).
-- Step 3: Redirect every FK table referencing dup asset IDs, then delete dups.
--
-- FK tables and strategy:
--   asset_relationships      (from_asset_id, to_asset_id) — compound PK → INSERT ON CONFLICT + DELETE
--   project_asset_access     (asset_id)                   — compound PK → INSERT ON CONFLICT + DELETE
--   detection_affected_asset (asset_id)                   — compound PK → INSERT ON CONFLICT + DELETE
--   detection                (asset_id)                   — own id PK   → UPDATE
--   affection_asset          (asset_id)  PK=(aff,asset,role) → INSERT ON CONFLICT + DELETE
--   affection_affects_link   (detected_asset_id, affects_asset_id) → INSERT ON CONFLICT + DELETE
--   web_endpoint_http_sample (asset_id)  — own id PK, CASCADE → UPDATE (must precede DELETE)
--   service_visibility       (service_asset_id) — own id PK   → UPDATE

-- Step 1
UPDATE ares.asset
   SET identifier = split_part(identifier, '?', 1),
       updated_at = now()
 WHERE type       = 'web_endpoint'
   AND identifier LIKE '%?%';

-- Step 2
CREATE TEMP TABLE _ep_dups AS
WITH ranked AS (
    SELECT id,
           MIN(id) OVER (PARTITION BY organization_id, identifier) AS keep_id
    FROM   ares.asset
    WHERE  type = 'web_endpoint'
)
SELECT id AS dup_id, keep_id FROM ranked WHERE id <> keep_id;

-- Step 3a: asset_relationships FROM
INSERT INTO ares.asset_relationships (from_asset_id, to_asset_id, type, directional, created_at)
SELECT DISTINCT d.keep_id, ar.to_asset_id, ar.type, ar.directional, ar.created_at
FROM   _ep_dups d JOIN ares.asset_relationships ar ON ar.from_asset_id = d.dup_id
ON CONFLICT (from_asset_id, to_asset_id, type) DO NOTHING;
DELETE FROM ares.asset_relationships ar USING _ep_dups d WHERE ar.from_asset_id = d.dup_id;

-- Step 3b: asset_relationships TO
INSERT INTO ares.asset_relationships (from_asset_id, to_asset_id, type, directional, created_at)
SELECT DISTINCT ar.from_asset_id, d.keep_id, ar.type, ar.directional, ar.created_at
FROM   _ep_dups d JOIN ares.asset_relationships ar ON ar.to_asset_id = d.dup_id
ON CONFLICT (from_asset_id, to_asset_id, type) DO NOTHING;
DELETE FROM ares.asset_relationships ar USING _ep_dups d WHERE ar.to_asset_id = d.dup_id;

-- Step 3c: project_asset_access
INSERT INTO ares.project_asset_access (project_id, asset_id)
SELECT DISTINCT paa.project_id, d.keep_id
FROM   _ep_dups d JOIN ares.project_asset_access paa ON paa.asset_id = d.dup_id
ON CONFLICT (project_id, asset_id) DO NOTHING;
DELETE FROM ares.project_asset_access paa USING _ep_dups d WHERE paa.asset_id = d.dup_id;

-- Step 3d: detection_affected_asset
INSERT INTO ares.detection_affected_asset (detection_id, asset_id)
SELECT DISTINCT daa.detection_id, d.keep_id
FROM   _ep_dups d JOIN ares.detection_affected_asset daa ON daa.asset_id = d.dup_id
ON CONFLICT (detection_id, asset_id) DO NOTHING;
DELETE FROM ares.detection_affected_asset daa USING _ep_dups d WHERE daa.asset_id = d.dup_id;

-- Step 3e: detection (own id PK, plain UPDATE)
UPDATE ares.detection SET asset_id = d.keep_id FROM _ep_dups d WHERE ares.detection.asset_id = d.dup_id;

-- Step 3f: affection_asset (PK = affection_id + asset_id + role)
INSERT INTO ares.affection_asset (affection_id, asset_id, role, observed_at, status)
SELECT DISTINCT aa.affection_id, d.keep_id, aa.role, aa.observed_at, aa.status
FROM   _ep_dups d JOIN ares.affection_asset aa ON aa.asset_id = d.dup_id
ON CONFLICT (affection_id, asset_id, role) DO NOTHING;
DELETE FROM ares.affection_asset aa USING _ep_dups d WHERE aa.asset_id = d.dup_id;

-- Step 3g: affection_affects_link — detected_asset_id column
INSERT INTO ares.affection_affects_link (affection_id, detected_asset_id, affects_asset_id)
SELECT DISTINCT aal.affection_id, d.keep_id, aal.affects_asset_id
FROM   _ep_dups d JOIN ares.affection_affects_link aal ON aal.detected_asset_id = d.dup_id
ON CONFLICT (affection_id, detected_asset_id, affects_asset_id) DO NOTHING;
DELETE FROM ares.affection_affects_link aal USING _ep_dups d WHERE aal.detected_asset_id = d.dup_id;

-- Step 3g (cont): affection_affects_link — affects_asset_id column
INSERT INTO ares.affection_affects_link (affection_id, detected_asset_id, affects_asset_id)
SELECT DISTINCT aal.affection_id, aal.detected_asset_id, d.keep_id
FROM   _ep_dups d JOIN ares.affection_affects_link aal ON aal.affects_asset_id = d.dup_id
ON CONFLICT (affection_id, detected_asset_id, affects_asset_id) DO NOTHING;
DELETE FROM ares.affection_affects_link aal USING _ep_dups d WHERE aal.affects_asset_id = d.dup_id;

-- Step 3h: web_endpoint_http_sample (own id PK, ON DELETE CASCADE — must UPDATE before asset delete)
UPDATE ares.web_endpoint_http_sample SET asset_id = d.keep_id FROM _ep_dups d WHERE ares.web_endpoint_http_sample.asset_id = d.dup_id;

-- Step 3i: service_visibility (own id PK, plain UPDATE)
UPDATE ares.service_visibility SET service_asset_id = d.keep_id FROM _ep_dups d WHERE ares.service_visibility.service_asset_id = d.dup_id;

-- Step 3j: Delete duplicate assets (all FK references cleared above).
DELETE FROM ares.asset a USING _ep_dups d WHERE a.id = d.dup_id;

DROP TABLE _ep_dups;
