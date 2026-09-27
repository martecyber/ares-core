-- "Scores" -> "Priorization": adds SSVC alongside CVSS, unifies findings and finding
-- templates onto the same is_default-driven severity model (previously templates derived
-- severity from max(all scores); findings didn't derive it from scores at all). A
-- finding/template with no default score now has no severity — NULL, shown as the
-- unknown-priority "P?" placeholder client-side — instead of being forced to 'medium'.

INSERT INTO finding_score_type (title, description)
SELECT 'SSVC', 'Stakeholder-Specific Vulnerability Categorization'
WHERE NOT EXISTS (SELECT 1 FROM finding_score_type WHERE title = 'SSVC');

ALTER TABLE finding_template_score
    ADD COLUMN IF NOT EXISTS is_default BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE finding          ALTER COLUMN severity DROP NOT NULL;
ALTER TABLE finding_template ALTER COLUMN severity DROP NOT NULL;
