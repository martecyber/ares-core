-- Security fix (user-reported): asset_tool_sighting (V96) was keyed only by (asset_id, tool) —
-- not project- or even org-scoped at all. Since Asset is organization-scoped and the SAME asset
-- row can be linked to multiple different projects within an org, this meant "Discovered by" on
-- an asset's detail page showed every tool sighting ever recorded for that asset from ANY
-- project's sync, even when viewed from a completely unrelated project's own asset page — and at
-- the organization level it surfaced raw per-project tool/scan provenance that should never be
-- visible outside the project that produced it.
--
-- Existing rows have no reliable project to backfill (the upsert previously had no way to record
-- one) — they're left with project_id NULL and simply excluded from both the project-scoped and
-- organization-scoped listings going forward (AssetToolSightingRepository), rather than guessed at
-- or shown under an ambiguous "unknown project" bucket. They'll reappear correctly scoped the next
-- time each project's own sync/import re-touches the asset.

ALTER TABLE ares.asset_tool_sighting ADD COLUMN project_id BIGINT REFERENCES ares.project(id) ON DELETE CASCADE;

ALTER TABLE ares.asset_tool_sighting DROP CONSTRAINT asset_tool_sighting_asset_id_tool_key;
ALTER TABLE ares.asset_tool_sighting ADD CONSTRAINT asset_tool_sighting_asset_id_tool_project_id_key
    UNIQUE (asset_id, tool, project_id);

CREATE INDEX idx_asset_tool_sighting_project_id ON ares.asset_tool_sighting (project_id);
