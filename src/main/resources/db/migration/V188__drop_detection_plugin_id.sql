-- detection.plugin_id (V6__asset_detection.sql) predates the dedup_hash/source_template_id
-- scheme V21__scan_imports.sql introduced. No import parser (Qualys, Tenable, ZAP, ...) has ever
-- populated it — sourceTemplateId is the field every parser actually uses for a tool's own
-- per-finding identifier (Tenable's plugin id, Qualys's QID, ZAP's plugin id, etc). The only way
-- plugin_id was ever set was a raw POST /detections body supplying it directly, which nothing in
-- this codebase (frontend included) has ever done. Dropping the column also drops
-- idx_detection_plugin (its only index) automatically — that partial index only covered rows
-- from before dedup_hash existed anyway (WHERE dedup_hash IS NULL).
ALTER TABLE ares.detection
    DROP COLUMN IF EXISTS plugin_id;
