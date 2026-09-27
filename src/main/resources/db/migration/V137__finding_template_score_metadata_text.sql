-- finding_template_score.metadata was declared JSONB (since V5) to hold a plain CVSS
-- vector / SSVC breadcrumb string — never an actual JSON document. FindingScore's
-- equivalent column (`vector`) is plain TEXT; this brings finding_template_score in line
-- with it.
--
-- @JdbcTypeCode(SqlTypes.JSON) on a Java String field does NOT auto-encode/decode: it
-- binds the String as raw JSON text both ways. So a plain vector string like
-- "CVSS:3.1/AV:N/..." was rejected by Postgres on insert ("invalid input syntax for type
-- json") once V136 removed the app-level manual JSON-string-wrap step that used to make
-- the raw text look like valid JSON (at the cost of the double-encoding bug V136 fixed).
-- Converting the column to plain text removes the need for either app-level encode or
-- decode. Existing rows are still jsonb string values (quoted) from before this fix —
-- `#>> '{}'` decodes them back to their plain text content.
ALTER TABLE ares.finding_template_score
    ALTER COLUMN metadata TYPE text USING (metadata #>> '{}');
