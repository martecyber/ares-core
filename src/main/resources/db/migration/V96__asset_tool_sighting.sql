-- Tracks which tool first and most recently discovered each asset.
-- Used by the asset detail view to show provenance history.
CREATE TABLE IF NOT EXISTS ares.asset_tool_sighting (
    id             bigserial   PRIMARY KEY,
    asset_id       bigint      NOT NULL REFERENCES ares.asset(id) ON DELETE CASCADE,
    tool           varchar(80) NOT NULL,
    first_seen_at  timestamptz NOT NULL DEFAULT now(),
    last_seen_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (asset_id, tool)
);
CREATE INDEX IF NOT EXISTS idx_asset_tool_sighting_asset_id ON ares.asset_tool_sighting (asset_id);
