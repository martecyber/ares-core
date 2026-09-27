-- V101: web_endpoint request-variable fields become arrays of distinct observed values;
-- relocate HTTP-probe facts that leaked from web_application onto the root web_endpoint;
-- drop redundant web_application.url; consolidate domain resolvedIp/ip into resolvedIps.
--
-- Helper: wraps any existing value as a jsonb array (handles legacy scalars defensively).
-- Dropped at the end of this migration — it only exists to keep the steps below readable.
CREATE OR REPLACE FUNCTION ares._to_jsonb_array(val jsonb) RETURNS jsonb AS $$
  SELECT CASE
    WHEN val IS NULL THEN '[]'::jsonb
    WHEN jsonb_typeof(val) = 'array' THEN val
    ELSE jsonb_build_array(val)
  END;
$$ LANGUAGE sql IMMUTABLE;

-- Step 1: web_endpoint — statusCode (Katana/httpx) + status (legacy ffuf) -> statusCodes[]
UPDATE ares.asset
   SET metadata = (metadata - 'statusCode' - 'status')
         || jsonb_build_object('statusCodes',
              (SELECT jsonb_agg(DISTINCT v) FROM jsonb_array_elements(
                 ares._to_jsonb_array(metadata->'statusCodes')
                 || CASE WHEN metadata ? 'statusCode' THEN jsonb_build_array(metadata->'statusCode') ELSE '[]'::jsonb END
                 || CASE WHEN metadata ? 'status'     THEN jsonb_build_array(metadata->'status')     ELSE '[]'::jsonb END
              ) AS v)),
       updated_at = now()
 WHERE type = 'web_endpoint'
   AND (metadata ? 'statusCode' OR metadata ? 'status');

-- Step 2: web_endpoint — contentType -> contentTypes[]
UPDATE ares.asset
   SET metadata = (metadata - 'contentType')
         || jsonb_build_object('contentTypes',
              (SELECT jsonb_agg(DISTINCT v) FROM jsonb_array_elements(
                 ares._to_jsonb_array(metadata->'contentTypes')
                 || jsonb_build_array(metadata->'contentType')
              ) AS v)),
       updated_at = now()
 WHERE type = 'web_endpoint' AND metadata ? 'contentType';

-- Step 3: web_endpoint — length -> lengths[]
UPDATE ares.asset
   SET metadata = (metadata - 'length')
         || jsonb_build_object('lengths',
              (SELECT jsonb_agg(DISTINCT v) FROM jsonb_array_elements(
                 ares._to_jsonb_array(metadata->'lengths')
                 || jsonb_build_array(metadata->'length')
              ) AS v)),
       updated_at = now()
 WHERE type = 'web_endpoint' AND metadata ? 'length';

-- Step 4: web_endpoint — words -> wordCounts[]
UPDATE ares.asset
   SET metadata = (metadata - 'words')
         || jsonb_build_object('wordCounts',
              (SELECT jsonb_agg(DISTINCT v) FROM jsonb_array_elements(
                 ares._to_jsonb_array(metadata->'wordCounts')
                 || jsonb_build_array(metadata->'words')
              ) AS v)),
       updated_at = now()
 WHERE type = 'web_endpoint' AND metadata ? 'words';

-- Step 5: web_endpoint — redirectLocation -> redirectLocations[]
UPDATE ares.asset
   SET metadata = (metadata - 'redirectLocation')
         || jsonb_build_object('redirectLocations',
              (SELECT jsonb_agg(DISTINCT v) FROM jsonb_array_elements(
                 ares._to_jsonb_array(metadata->'redirectLocations')
                 || jsonb_build_array(metadata->'redirectLocation')
              ) AS v)),
       updated_at = now()
 WHERE type = 'web_endpoint' AND metadata ? 'redirectLocation';

-- Step 6: web_endpoint — source -> sources[]
UPDATE ares.asset
   SET metadata = (metadata - 'source')
         || jsonb_build_object('sources',
              (SELECT jsonb_agg(DISTINCT v) FROM jsonb_array_elements(
                 ares._to_jsonb_array(metadata->'sources')
                 || jsonb_build_array(metadata->'source')
              ) AS v)),
       updated_at = now()
 WHERE type = 'web_endpoint' AND metadata ? 'source';

-- Step 7: web_application -> root web_endpoint (same org + identifier): relocate title/server
-- (kept scalar, only set if the endpoint doesn't already have one) and fold statusCode into
-- the endpoint's statusCodes[]. Rows with no matching root web_endpoint are handled in step 8
-- (the stray fields are simply dropped — see migration plan for the trade-off).
UPDATE ares.asset we
   SET metadata = we.metadata
         || jsonb_strip_nulls(jsonb_build_object(
              'title',  CASE WHEN we.metadata ? 'title'  THEN NULL ELSE wa.metadata->'title'  END,
              'server', CASE WHEN we.metadata ? 'server' THEN NULL ELSE wa.metadata->'server' END
            ))
         || jsonb_strip_nulls(jsonb_build_object('statusCodes',
              (SELECT jsonb_agg(DISTINCT v) FROM jsonb_array_elements(
                 ares._to_jsonb_array(we.metadata->'statusCodes')
                 || CASE WHEN wa.metadata ? 'statusCode' THEN jsonb_build_array(wa.metadata->'statusCode') ELSE '[]'::jsonb END
              ) AS v))),
       updated_at = now()
  FROM ares.asset wa
 WHERE wa.type = 'web_application'
   AND we.type = 'web_endpoint'
   AND we.organization_id = wa.organization_id
   AND we.identifier = wa.identifier
   AND (wa.metadata ? 'title' OR wa.metadata ? 'server' OR wa.metadata ? 'statusCode');

-- Step 8: web_application — drop the relocated keys regardless of whether step 7 found a
-- matching root endpoint (it always should, ensureWebEndpoints() creates one per import).
UPDATE ares.asset
   SET metadata = metadata - 'title' - 'server' - 'statusCode',
       updated_at = now()
 WHERE type = 'web_application'
   AND (metadata ? 'title' OR metadata ? 'server' OR metadata ? 'statusCode');

-- Step 9: web_application — drop "url" when it's just a duplicate of the asset's own identifier.
UPDATE ares.asset
   SET metadata = metadata - 'url',
       updated_at = now()
 WHERE type = 'web_application'
   AND metadata ? 'url'
   AND metadata->>'url' = identifier;

-- Step 10: domain — resolvedIp (Nmap) + ip (Shodan) -> resolvedIps[]
UPDATE ares.asset
   SET metadata = (metadata - 'resolvedIp' - 'ip')
         || jsonb_build_object('resolvedIps',
              (SELECT jsonb_agg(DISTINCT v) FROM jsonb_array_elements(
                 ares._to_jsonb_array(metadata->'resolvedIps')
                 || CASE WHEN metadata ? 'resolvedIp' THEN jsonb_build_array(metadata->'resolvedIp') ELSE '[]'::jsonb END
                 || CASE WHEN metadata ? 'ip'          THEN jsonb_build_array(metadata->'ip')          ELSE '[]'::jsonb END
              ) AS v)),
       updated_at = now()
 WHERE type = 'domain'
   AND (metadata ? 'resolvedIp' OR metadata ? 'ip');

DROP FUNCTION ares._to_jsonb_array(jsonb);
