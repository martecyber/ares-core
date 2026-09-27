-- DetectionReferenceExtractor historically created CVE/CWE reference_entry rows with title only
-- (no description) — unlike the ReferencesDialog-driven Finding flow, which always stores a real
-- CVE description / CWE name at creation time. This backfills every already-extracted row whose
-- id is present in the locally-synced KB corpus, matching what CweXmlParser.java's counterpart
-- fix (extractor now sets description going forward) does for new rows.

UPDATE ares.reference_entry re
SET description = LEFT(c.description, 200)
FROM ares.reference_catalog rc, ares.cve c
WHERE re.catalog_id = rc.id
  AND rc.code = 'CVE'
  AND (re.description IS NULL OR re.description = '')
  AND upper(re.title) = upper(c.cve_id)
  AND c.description IS NOT NULL;

UPDATE ares.reference_entry re
SET description = w.name
FROM ares.reference_catalog rc, ares.cwe w
WHERE re.catalog_id = rc.id
  AND rc.code = 'CWE'
  AND (re.description IS NULL OR re.description = '')
  AND upper(re.title) = upper('CWE-' || w.cwe_id)
  AND w.name IS NOT NULL;
