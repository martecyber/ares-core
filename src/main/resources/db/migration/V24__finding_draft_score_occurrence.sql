-- Draft flag on findings (no code until reported)
ALTER TABLE ares.finding
    ADD COLUMN IF NOT EXISTS is_draft BOOLEAN NOT NULL DEFAULT TRUE;

-- Existing findings already have codes → not drafts
UPDATE ares.finding SET is_draft = FALSE WHERE code IS NOT NULL;

-- CVSS vector string + default flag on finding_score
ALTER TABLE ares.finding_score
    ADD COLUMN IF NOT EXISTS vector      TEXT,
    ADD COLUMN IF NOT EXISTS is_default  BOOLEAN NOT NULL DEFAULT FALSE;

-- Occurrence context fields
ALTER TABLE ares.occurrence
    ADD COLUMN IF NOT EXISTS title       VARCHAR(200),
    ADD COLUMN IF NOT EXISTS description TEXT;

-- Score type seeds: ensure common types exist
INSERT INTO ares.finding_score_type (title, description)
SELECT 'CVSS 3.1', 'Common Vulnerability Scoring System v3.1'
WHERE NOT EXISTS (SELECT 1 FROM ares.finding_score_type WHERE title = 'CVSS 3.1');

INSERT INTO ares.finding_score_type (title, description)
SELECT 'CVSS 2.0', 'Common Vulnerability Scoring System v2.0'
WHERE NOT EXISTS (SELECT 1 FROM ares.finding_score_type WHERE title = 'CVSS 2.0');

INSERT INTO ares.finding_score_type (title, description)
SELECT 'Textual', 'Qualitative risk assessment (0–10 scale)'
WHERE NOT EXISTS (SELECT 1 FROM ares.finding_score_type WHERE title = 'Textual');
