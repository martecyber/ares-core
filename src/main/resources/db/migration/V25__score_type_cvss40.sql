INSERT INTO ares.finding_score_type (title, description)
SELECT 'CVSS 4.0', 'Common Vulnerability Scoring System v4.0'
WHERE NOT EXISTS (SELECT 1 FROM ares.finding_score_type WHERE title = 'CVSS 4.0');
