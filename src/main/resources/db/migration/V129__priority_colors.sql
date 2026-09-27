-- "Severity colors" -> "Priority colors": same per-level {bgColor, textColor} config on a
-- report template, renamed for consistency with the rest of the P0-P4 nomenclature, plus a
-- new optional "label" per level — the literal text stamped into the generated report for
-- findings of that priority (defaults to the P0-P4 label when left unset).
ALTER TABLE report_template RENAME COLUMN severity_colors TO priority_colors;
