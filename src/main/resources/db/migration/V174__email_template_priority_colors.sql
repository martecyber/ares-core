SET search_path TO ares, public;

-- Per-severity severityLabel/severityColor override, mirroring report_template.priority_colors —
-- moves this from a single platform-wide admin setting (ares.platform_setting) to per-template
-- config, the same as DOCX report templates already work.
ALTER TABLE ares.email_template ADD COLUMN priority_colors JSONB;
