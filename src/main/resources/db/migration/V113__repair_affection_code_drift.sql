-- Repair affection codes that drifted from their finding's code.
-- Affection.code is derived once at creation time as {finding.code}-{n}, but until now
-- FindingService.doPublish() never re-derived it when a draft's temp code (e.g.
-- "ENG-TEMP-3") was replaced by the finding's final published code (e.g. "ENG-12") — leaving
-- already-published findings' affections stuck referencing the old temp prefix.
UPDATE ares.affection a
SET code = sub.affection_code,
    updated_at = now()
FROM (
    SELECT
        a.id,
        f.code
            || '-'
            || ROW_NUMBER() OVER (PARTITION BY a.finding_id ORDER BY a.created_at ASC, a.id ASC) AS affection_code
    FROM ares.affection a
    JOIN ares.finding f ON f.id = a.finding_id
    WHERE f.is_draft = false AND f.code IS NOT NULL
) sub
WHERE a.id = sub.id AND a.code IS DISTINCT FROM sub.affection_code;
