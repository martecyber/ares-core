-- External tool ids (Tenable asset.id, Qualys host id, CrowdStrike device_id, …) were unique per
-- (tool, external_id) across the whole platform, so the same id seen in two organizations (a shared
-- Greenbone/Qualys instance, a re-imported file) resolved the second org's import to the first
-- org's asset. Scope the mapping to the asset's organization.
ALTER TABLE ares.asset_external_id ADD COLUMN organization_id BIGINT;

UPDATE ares.asset_external_id e
   SET organization_id = a.organization_id
  FROM ares.asset a
 WHERE a.id = e.asset_id;

ALTER TABLE ares.asset_external_id
    ALTER COLUMN organization_id SET NOT NULL,
    ADD CONSTRAINT fk_asset_external_id_org FOREIGN KEY (organization_id)
        REFERENCES ares.organization(id) ON DELETE CASCADE,
    DROP CONSTRAINT IF EXISTS asset_external_id_tool_external_id_key,
    ADD CONSTRAINT uq_asset_external_id_org_tool_ext UNIQUE (organization_id, tool, external_id);
