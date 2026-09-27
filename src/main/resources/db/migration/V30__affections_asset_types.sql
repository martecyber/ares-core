SET search_path TO ares, public;

-- ── Rename occurrence tables → affection ─────────────────────────────────────

ALTER TABLE ares.occurrence           RENAME TO affection;
ALTER TABLE ares.occurrence_detection RENAME TO affection_detection;
ALTER TABLE ares.occurrence_asset     RENAME TO affection_asset;

ALTER TABLE ares.affection_detection RENAME COLUMN occurrence_id TO affection_id;
ALTER TABLE ares.affection_asset     RENAME COLUMN occurrence_id TO affection_id;

-- Add title/description if not already present (may have been added in V24)
ALTER TABLE ares.affection ADD COLUMN IF NOT EXISTS title       VARCHAR(200);
ALTER TABLE ares.affection ADD COLUMN IF NOT EXISTS description TEXT;

-- ── Add role column to affection_asset ───────────────────────────────────────
-- 'detected_at' = asset where scanner found the issue
-- 'affects'     = high-level asset the issue actually affects (manually assigned)

ALTER TABLE ares.affection_asset ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'detected_at';

-- Expand PK to include role so the same asset can appear in both roles
ALTER TABLE ares.affection_asset DROP CONSTRAINT occurrence_asset_pkey;
ALTER TABLE ares.affection_asset ADD CONSTRAINT affection_asset_pkey
    PRIMARY KEY (affection_id, asset_id, role);

-- Rename detection PK for consistency
ALTER TABLE ares.affection_detection DROP CONSTRAINT occurrence_detection_pkey;
ALTER TABLE ares.affection_detection ADD CONSTRAINT affection_detection_pkey
    PRIMARY KEY (affection_id, detection_id);

-- Rename index on affection.finding_id
ALTER INDEX IF EXISTS ares.ix_occurrence_finding RENAME TO ix_affection_finding;

-- ── Asset type documentation (enforced at service layer) ──────────────────────
COMMENT ON COLUMN ares.asset.type IS
    'Canonical types: host, ip, service, interface, domain, web_application, system, directory';

COMMENT ON COLUMN ares.asset_relationships.type IS
    'Valid link types: host_interface, interface_ip, interface_service, '
    'domain_cname, domain_a, domain_srv, domain_host, '
    'webapp_service, system_host, directory_domain, directory_host';

COMMENT ON COLUMN ares.affection_asset.role IS
    'detected_at = scanner-observed asset; affects = high-level impacted asset (host/webapp/system/directory)';
