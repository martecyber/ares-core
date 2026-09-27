-- Backfill: scope entries of kind url/url_wildcard that were stored without a protocol
-- prefix (e.g. "sgnl.ai") get https:// prepended so the classifier can match them
-- against WEB_APPLICATION identifiers which always carry a scheme.
UPDATE ares.project_scope_entry
SET value = 'https://' || value
WHERE kind IN ('url', 'url_wildcard')
  AND value NOT LIKE '%://%';
