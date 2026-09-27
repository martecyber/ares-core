-- CWE moves from MongoDB (kb_cwe) to Postgres (AQL-wide initiative, Phase 5). Scalars stay real
-- columns; parent_ids/child_ids/related_capec_ids/applicable_platforms/observed_examples are
-- native text[] (HAS-queryable, GIN-indexed) — fixed-schema scalar-code lists, same precedent as
-- cve.cwes. consequences/mitigations/vulnerability_mapping stay JSONB — display-oriented
-- structured data with no AQL query requirement (confirmed decision from the AQL-wide plan).

CREATE TABLE ares.cwe (
    id                     BIGSERIAL PRIMARY KEY,
    cwe_id                 TEXT NOT NULL,
    code                   TEXT,
    name                   TEXT,
    type                   TEXT,
    abstraction            TEXT,
    status                 TEXT,
    description            TEXT,
    extended_description   TEXT,
    consequences           JSONB NOT NULL DEFAULT '[]',
    mitigations            JSONB NOT NULL DEFAULT '[]',
    parent_ids             TEXT[] NOT NULL DEFAULT '{}',
    child_ids              TEXT[] NOT NULL DEFAULT '{}',
    related_capec_ids      TEXT[] NOT NULL DEFAULT '{}',
    likelihood_of_exploit  TEXT,
    applicable_platforms   TEXT[] NOT NULL DEFAULT '{}',
    observed_examples      TEXT[] NOT NULL DEFAULT '{}',
    vulnerability_mapping  JSONB,
    synced_at              TIMESTAMPTZ,
    source_version         TEXT
);

CREATE UNIQUE INDEX ux_cwe_cwe_id ON ares.cwe(cwe_id);
CREATE INDEX ix_cwe_type ON ares.cwe(type);
CREATE INDEX ix_cwe_abstraction ON ares.cwe(abstraction);
CREATE INDEX ix_cwe_status ON ares.cwe(status);
CREATE INDEX ix_cwe_synced_at ON ares.cwe(synced_at);
CREATE INDEX ix_cwe_parent_ids ON ares.cwe USING GIN(parent_ids);
CREATE INDEX ix_cwe_child_ids ON ares.cwe USING GIN(child_ids);
CREATE INDEX ix_cwe_related_capec_ids ON ares.cwe USING GIN(related_capec_ids);
CREATE INDEX ix_cwe_applicable_platforms ON ares.cwe USING GIN(applicable_platforms);
CREATE INDEX ix_cwe_observed_examples ON ares.cwe USING GIN(observed_examples);
