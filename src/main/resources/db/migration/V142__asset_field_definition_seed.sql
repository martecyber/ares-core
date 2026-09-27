-- AQL implementation plan, Phase 0: seed field_definition for Asset's metadata jsonb.
--
-- Deliberately minimal. Per the plan's own caution, this must reflect real usage rather than a
-- guessed schema — an audit of this environment's actual data (`SELECT DISTINCT
-- jsonb_object_keys(metadata) FROM ares.asset GROUP BY type`) found essentially no metadata in
-- use yet (a single test asset with empty metadata), and a grep across ares-ui found no per-type
-- typed metadata form/schema either — metadata is treated as an opaque Record<string, unknown>
-- everywhere except one confirmed real key. So only that one key is seeded here; the rest of the
-- per-asset-type schema is intentionally deferred until there's real data (or a deliberate
-- product decision) to audit against, rather than fabricated now.
--
-- Note: Asset.hostSubtype is NOT a metadata key — it's Asset's own `host_subtype` column
-- (V100__asset_host_subtype.sql), so it does not belong in field_definition (which is scoped to
-- the jsonb dynamic-field bag). It'll become its own PHYSICAL_COLUMN AqlField directly on
-- AssetAqlRegistry in Phase 3, not a field_definition row.

INSERT INTO ares.field_definition (entity_type, asset_type, field_key, title, data_type, allowed_values, sort_order, is_system, created_at)
VALUES (
    'asset', 'interface', 'interfaceType', 'Interface Type', 'enum',
    '["ethernet", "wireless", "virtual", "loopback", "other"]'::jsonb,
    0, TRUE, NOW()
);
