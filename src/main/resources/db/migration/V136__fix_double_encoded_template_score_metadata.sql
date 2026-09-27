-- FindingTemplateService used to run a value like "CVSS:4.0/AV:N/.../SA:H" (or an SSVC
-- breadcrumb) through a manual toJsonValue() wrap-as-JSON-string step before assigning it
-- to FindingTemplateScore.metadata — a field already mapped @JdbcTypeCode(SqlTypes.JSON),
-- which independently JSON-encodes whatever Java String it's given. The two layers
-- combined double-encoded every value: the jsonb column ended up holding a JSON string
-- whose *content* was itself a quoted JSON string, so reading it back through Hibernate's
-- one layer of decoding left literal leading/trailing " characters (and any internal
-- quotes double-escaped) baked into the text shown in the UI — e.g. a CVSS vector or an
-- SSVC outcome badge rendering with a stray " next to it.
--
-- toJsonValue() has been removed (see FindingTemplateService.java) so this can't happen
-- for new writes; this one-off backfill un-doubles every already-affected row. Detected
-- by: the value is a JSON string (jsonb_typeof = 'string') whose own text content still
-- looks like a quoted JSON string literal (starts and ends with '"') — true only for the
-- double-encoded rows, since a correctly single-encoded CVSS vector/SSVC breadcrumb never
-- itself starts and ends with a literal quote character.
UPDATE ares.finding_template_score
SET metadata = to_jsonb((metadata #>> '{}')::jsonb #>> '{}')
WHERE metadata IS NOT NULL
  AND jsonb_typeof(metadata) = 'string'
  AND length(metadata #>> '{}') >= 2
  AND left(metadata #>> '{}', 1) = '"'
  AND right(metadata #>> '{}', 1) = '"';
