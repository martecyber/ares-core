-- asset_relationships' PK (from_asset_id, to_asset_id, type) only serves lookups
-- leading with from_asset_id. Any query filtering by to_asset_id alone (or via OR
-- with from_asset_id, as the asset-graph neighborhood traversal does) falls back to
-- a full sequential scan on this table — painfully slow once an org accumulates a
-- large relationship graph (subdomain enumeration, etc).
CREATE INDEX IF NOT EXISTS ix_asset_relationships_to_asset_id
    ON ares.asset_relationships(to_asset_id);
