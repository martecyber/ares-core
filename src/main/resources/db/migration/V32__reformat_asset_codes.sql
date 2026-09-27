-- Reformat all asset codes to {ORG_SLUG}-{TYPE_CODE}-N
-- N is assigned per (organization, type) ordered by created_at, id
-- so older assets keep lower numbers.

WITH numbered AS (
    SELECT
        a.id,
        UPPER(o.slug)
            || '-'
            || CASE a.type
                   WHEN 'host'            THEN 'HOST'
                   WHEN 'ip'              THEN 'IP'
                   WHEN 'service'         THEN 'SVC'
                   WHEN 'interface'       THEN 'IFACE'
                   WHEN 'domain'          THEN 'DOM'
                   WHEN 'web_application' THEN 'WEBAPP'
                   WHEN 'system'          THEN 'SYS'
                   WHEN 'directory'       THEN 'DIR'
                   WHEN 'network'         THEN 'NET'
                   ELSE UPPER(REPLACE(a.type, '_', '-'))
               END
            || '-'
            || ROW_NUMBER() OVER (
                   PARTITION BY a.organization_id, a.type
                   ORDER BY a.created_at, a.id
               )::TEXT  AS new_code
    FROM ares.asset        a
    JOIN ares.organization o ON o.id = a.organization_id
)
UPDATE ares.asset a
SET    code = n.new_code
FROM   numbered n
WHERE  a.id = n.id;
