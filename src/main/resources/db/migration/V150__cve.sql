-- CVE moves from MongoDB (kb_cve) to Postgres (AQL-wide initiative, Phase 3 — the flagship KB
-- migration). Scalars stay real columns; cwes is a native text[] (HAS-queryable, GIN-indexed);
-- affected_products/references/cvss_scores/ssvc stay JSONB — display-oriented structured data
-- with no AQL query requirement, matching this codebase's dominant existing pattern for exactly
-- this shape of data (89 other jsonb columns already do the same).

CREATE TABLE ares.cve (
    id                        BIGSERIAL PRIMARY KEY,
    cve_id                    TEXT NOT NULL,
    state                     TEXT,
    description               TEXT,
    cvss_score                DOUBLE PRECISION,
    cvss_vector               TEXT,
    cvss_version              TEXT,
    severity                  TEXT,
    cwes                      TEXT[] NOT NULL DEFAULT '{}',
    affected_products         JSONB NOT NULL DEFAULT '[]',
    refs                      JSONB NOT NULL DEFAULT '[]',
    cvss_scores               JSONB NOT NULL DEFAULT '[]',
    ssvc                      JSONB,
    published_at              TIMESTAMPTZ,
    last_modified_at          TIMESTAMPTZ,
    synced_at                 TIMESTAMPTZ,
    kev_listed                BOOLEAN NOT NULL DEFAULT FALSE,
    kev_date_added            DATE,
    vulncheck_kev_listed      BOOLEAN NOT NULL DEFAULT FALSE,
    vulncheck_kev_date_added  DATE,
    any_kev_listed            BOOLEAN NOT NULL DEFAULT FALSE,
    exploit_count             INTEGER NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX ux_cve_cve_id ON ares.cve(cve_id);
CREATE INDEX ix_cve_severity ON ares.cve(severity);
CREATE INDEX ix_cve_any_kev_listed ON ares.cve(any_kev_listed);
CREATE INDEX ix_cve_exploit_count ON ares.cve(exploit_count);
CREATE INDEX ix_cve_published_at ON ares.cve(published_at);
CREATE INDEX ix_cve_last_modified_at ON ares.cve(last_modified_at);
CREATE INDEX ix_cve_cwes ON ares.cve USING GIN(cwes);

-- Singleton row tracking the last completed CVE git sync (was kb_cve_sync_state in Mongo).
CREATE TABLE ares.cve_sync_state (
    id                TEXT PRIMARY KEY DEFAULT 'singleton',
    last_commit       TEXT,
    last_synced_at    TIMESTAMPTZ,
    total_processed   BIGINT NOT NULL DEFAULT 0
);
