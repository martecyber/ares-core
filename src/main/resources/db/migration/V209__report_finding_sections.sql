-- ── Named finding sections per report ────────────────────────────────────────
-- A report's findings can now be split into named sections (e.g. "new" vs "previous") that a
-- template addresses as separate loop variables. Rows that existed before this migration, and
-- every finding picked without a section, belong to the reserved 'default' section — the one
-- the classic {{?findings}} variable renders.
ALTER TABLE report_finding
    ADD COLUMN section_variable VARCHAR(64)  NOT NULL DEFAULT 'default',
    ADD COLUMN section_label    VARCHAR(100);

ALTER TABLE report_finding DROP CONSTRAINT report_finding_pkey;
ALTER TABLE report_finding ADD PRIMARY KEY (report_id, finding_id, section_variable);
