-- CAPEC's own capec_id is stored bare-numeric ("63"), unlike CWE which already carries both a
-- bare cwe_id and a prefixed code ("CWE-79") column. AQL's capec.id now needs the same full
-- "CAPEC-63" code every other X-N-style catalog (CWE, CVE) already exposes as its identity, so
-- this adds the same code column CweEntry has, backfilled from the existing capec_id — no CAPEC
-- resync required for this to take effect immediately.

ALTER TABLE ares.capec ADD COLUMN code TEXT;
UPDATE ares.capec SET code = 'CAPEC-' || capec_id WHERE code IS NULL;
ALTER TABLE ares.capec ALTER COLUMN code SET NOT NULL;
CREATE UNIQUE INDEX ux_capec_code ON ares.capec(code);
