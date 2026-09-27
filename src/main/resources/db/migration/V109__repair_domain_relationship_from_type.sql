-- V109: repair domain_* asset_relationships whose from_asset_id points at a non-domain
-- asset (historically HOST) instead of a DOMAIN asset. Caused by an identifier-collision
-- bug in ImportService's asset dedup (fixed in application code, see AssetLinkType /
-- ImportService), which silently dropped a DOMAIN ParsedAsset whenever it shared an
-- identifier string with a HOST ParsedAsset (e.g. a PTR-resolved hostname with no
-- explicit user-typed hostname). For each affected relationship: find or create the
-- DOMAIN asset sharing the wrong from-asset's (organization_id, identifier), copy the
-- wrong asset's project visibility onto it, re-point the relationship, then drop the
-- bad row. Naturally idempotent: once repaired, the from-asset.type <> 'domain'
-- predicate matches nothing on a second run.

-- Step 1: create any missing DOMAIN assets needed to repair bad relationships.
WITH bad_rels AS (
    SELECT DISTINCT a_from.organization_id, a_from.identifier
    FROM ares.asset_relationships ar
    JOIN ares.asset a_from ON a_from.id = ar.from_asset_id
    WHERE ar.type IN ('domain_cname','domain_a','domain_aaaa','domain_mx','domain_ns',
                       'domain_txt','domain_ptr','domain_soa','domain_caa','domain_srv','domain_host')
      AND a_from.type <> 'domain'
),
missing_domains AS (
    SELECT br.organization_id, br.identifier
    FROM bad_rels br
    WHERE NOT EXISTS (
        SELECT 1 FROM ares.asset a
        WHERE a.organization_id = br.organization_id
          AND a.type = 'domain'
          AND a.identifier = br.identifier
    )
),
numbered AS (
    SELECT md.organization_id, md.identifier,
           ROW_NUMBER() OVER (PARTITION BY md.organization_id ORDER BY md.identifier)
             + COALESCE((
                 SELECT MAX(SUBSTRING(a2.code FROM '\-DOM\-(\d+)$')::INT)
                 FROM ares.asset a2
                 WHERE a2.organization_id = md.organization_id
                   AND a2.type = 'domain'
                   AND a2.code ~ '\-DOM\-\d+$'
               ), 0) AS seq_no
    FROM missing_domains md
)
INSERT INTO ares.asset (organization_id, type, identifier, code, created_at, updated_at)
SELECT n.organization_id, 'domain', n.identifier,
       UPPER(o.slug) || '-DOM-' || n.seq_no::TEXT,
       now(), now()
FROM numbered n
JOIN ares.organization o ON o.id = n.organization_id;

-- Step 2: copy project visibility from the wrong from-asset onto the repaired domain asset.
INSERT INTO ares.project_asset_access (project_id, asset_id, scope_status, scope_override)
SELECT DISTINCT paa.project_id, dom.id, 'indeterminate', false
FROM ares.asset_relationships ar
JOIN ares.asset a_from ON a_from.id = ar.from_asset_id
JOIN ares.asset dom ON dom.organization_id = a_from.organization_id
                   AND dom.type = 'domain'
                   AND dom.identifier = a_from.identifier
JOIN ares.project_asset_access paa ON paa.asset_id = a_from.id
WHERE ar.type IN ('domain_cname','domain_a','domain_aaaa','domain_mx','domain_ns',
                   'domain_txt','domain_ptr','domain_soa','domain_caa','domain_srv','domain_host')
  AND a_from.type <> 'domain'
ON CONFLICT (project_id, asset_id) DO NOTHING;

-- Step 3: insert the corrected domain -> X relationships.
INSERT INTO ares.asset_relationships (from_asset_id, to_asset_id, type, directional, created_at)
SELECT dom.id, ar.to_asset_id, ar.type, ar.directional, ar.created_at
FROM ares.asset_relationships ar
JOIN ares.asset a_from ON a_from.id = ar.from_asset_id
JOIN ares.asset dom ON dom.organization_id = a_from.organization_id
                   AND dom.type = 'domain'
                   AND dom.identifier = a_from.identifier
WHERE ar.type IN ('domain_cname','domain_a','domain_aaaa','domain_mx','domain_ns',
                   'domain_txt','domain_ptr','domain_soa','domain_caa','domain_srv','domain_host')
  AND a_from.type <> 'domain'
ON CONFLICT (from_asset_id, to_asset_id, type) DO NOTHING;

-- Step 4: delete the old bad rows, now that corrected replacements exist.
DELETE FROM ares.asset_relationships ar
USING ares.asset a_from
WHERE a_from.id = ar.from_asset_id
  AND ar.type IN ('domain_cname','domain_a','domain_aaaa','domain_mx','domain_ns',
                   'domain_txt','domain_ptr','domain_soa','domain_caa','domain_srv','domain_host')
  AND a_from.type <> 'domain';
