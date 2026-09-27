ALTER TABLE ares.report_template
    ADD COLUMN severity_colors JSONB,
    ADD COLUMN magic_color     VARCHAR(6) DEFAULT 'FF00FF';
