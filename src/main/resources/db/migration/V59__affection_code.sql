-- Add code column to affection table
ALTER TABLE ares.affection ADD COLUMN code VARCHAR(80);

-- Populate existing affections: {finding_code}-{seq} ordered by created_at ASC
UPDATE ares.affection a
SET code = sub.affection_code
FROM (
    SELECT
        a.id,
        COALESCE(f.code, 'F' || a.finding_id)
            || '-'
            || ROW_NUMBER() OVER (PARTITION BY a.finding_id ORDER BY a.created_at ASC, a.id ASC) AS affection_code
    FROM ares.affection a
    JOIN ares.finding f ON f.id = a.finding_id
) sub
WHERE a.id = sub.id;

ALTER TABLE ares.affection ALTER COLUMN code SET NOT NULL;
CREATE UNIQUE INDEX affection_code_unique ON ares.affection (code);
