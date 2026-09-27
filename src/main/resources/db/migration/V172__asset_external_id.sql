-- Stable per-tool identity for HOST assets (Tenable asset.id, Greenbone/GVM asset_id, ...),
-- used to resolve the same host across syncs even when its active IP changes (DHCP reassignment,
-- agent vs. network scan reporting a different NIC) instead of relying solely on IP/interface
-- matching, which silently created duplicate hosts when the active IP drifted.
CREATE TABLE IF NOT EXISTS ares.asset_external_id (
    id            bigserial    PRIMARY KEY,
    asset_id      bigint       NOT NULL REFERENCES ares.asset(id) ON DELETE CASCADE,
    tool          varchar(80)  NOT NULL,
    external_id   varchar(255) NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (tool, external_id)
);
CREATE INDEX IF NOT EXISTS idx_asset_external_id_asset_id ON ares.asset_external_id (asset_id);
