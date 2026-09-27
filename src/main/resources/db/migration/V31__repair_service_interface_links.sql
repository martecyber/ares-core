-- Repair missing interface_service links for existing data.
--
-- Pattern: service identifier = "{ip}:{port}/{proto}"
--          interface identifier = "iface-{ip}" (no-MAC case, most common)
--
-- For each interface of the form "iface-{ip}", find services in the same
-- organisation whose identifier starts with "{ip}:" and create the
-- interface_service link if it does not already exist.

INSERT INTO ares.asset_relationships (from_asset_id, to_asset_id, type, directional, created_at)
SELECT DISTINCT
    iface.id   AS from_asset_id,
    svc.id     AS to_asset_id,
    'interface_service' AS type,
    TRUE        AS directional,
    NOW()       AS created_at
FROM ares.asset iface
JOIN ares.asset svc
    ON  svc.organization_id = iface.organization_id
    AND svc.type            = 'service'
    -- service identifier starts with the IP that follows "iface-"
    AND svc.identifier LIKE (SUBSTRING(iface.identifier FROM 7) || ':%')
WHERE iface.type       = 'interface'
  AND iface.identifier LIKE 'iface-%'
  -- only create links that do not already exist
  AND NOT EXISTS (
      SELECT 1
      FROM   ares.asset_relationships r
      WHERE  r.from_asset_id = iface.id
        AND  r.to_asset_id   = svc.id
        AND  r.type          = 'interface_service'
  );
