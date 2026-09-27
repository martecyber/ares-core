-- OWASP moves from MongoDB (kb_owasp) to Postgres (AQL-wide initiative, Phase 5 — simplest of the
-- four remaining KB catalogs: flat scalars plus two flat string lists, no nested records).

CREATE TABLE ares.owasp (
    id           BIGSERIAL PRIMARY KEY,
    owasp_id     TEXT NOT NULL,
    year         INTEGER NOT NULL,
    rank         INTEGER NOT NULL,
    name         TEXT,
    description  TEXT,
    cwes         TEXT[] NOT NULL DEFAULT '{}',
    preventions  TEXT[] NOT NULL DEFAULT '{}',
    synced_at    TIMESTAMPTZ
);

-- Same (id, year) natural key as the old Mongo unique-ish upsert (an OWASP rank id like "A01"
-- recurs across editions with different content each time).
CREATE UNIQUE INDEX ux_owasp_owaspid_year ON ares.owasp(owasp_id, year);
CREATE INDEX ix_owasp_year ON ares.owasp(year);
CREATE INDEX ix_owasp_rank ON ares.owasp(rank);
CREATE INDEX ix_owasp_cwes ON ares.owasp USING GIN(cwes);
