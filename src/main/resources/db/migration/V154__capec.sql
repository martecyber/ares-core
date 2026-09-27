-- CAPEC moves from MongoDB (kb_capec) to Postgres (AQL-wide initiative, Phase 5). Flat-string
-- lists become native text[] (HAS-queryable, GIN-indexed) — prerequisites/mitigations (CAPEC's
-- own mitigations are flat strings, unlike CWE's structured records)/related_cwe_ids/
-- related_attack_technique_ids/parent_capec_ids/child_capec_ids/domains. consequences/
-- execution_flow stay JSONB — structured, display-only, no AQL query requirement (same decision
-- as CWE's consequences/mitigations).

CREATE TABLE ares.capec (
    id                            BIGSERIAL PRIMARY KEY,
    capec_id                      TEXT NOT NULL,
    name                          TEXT,
    abstraction                   TEXT,
    status                        TEXT,
    description                   TEXT,
    extended_description          TEXT,
    typical_severity              TEXT,
    likelihood_of_attack          TEXT,
    prerequisites                 TEXT[] NOT NULL DEFAULT '{}',
    mitigations                   TEXT[] NOT NULL DEFAULT '{}',
    consequences                  JSONB NOT NULL DEFAULT '[]',
    related_cwe_ids               TEXT[] NOT NULL DEFAULT '{}',
    related_attack_technique_ids  TEXT[] NOT NULL DEFAULT '{}',
    parent_capec_ids              TEXT[] NOT NULL DEFAULT '{}',
    child_capec_ids               TEXT[] NOT NULL DEFAULT '{}',
    domains                       TEXT[] NOT NULL DEFAULT '{}',
    execution_flow                JSONB NOT NULL DEFAULT '[]',
    synced_at                     TIMESTAMPTZ,
    source_version                TEXT
);

CREATE UNIQUE INDEX ux_capec_capec_id ON ares.capec(capec_id);
CREATE INDEX ix_capec_abstraction ON ares.capec(abstraction);
CREATE INDEX ix_capec_status ON ares.capec(status);
CREATE INDEX ix_capec_typical_severity ON ares.capec(typical_severity);
CREATE INDEX ix_capec_synced_at ON ares.capec(synced_at);
CREATE INDEX ix_capec_related_cwe_ids ON ares.capec USING GIN(related_cwe_ids);
CREATE INDEX ix_capec_related_attack_technique_ids ON ares.capec USING GIN(related_attack_technique_ids);
CREATE INDEX ix_capec_parent_capec_ids ON ares.capec USING GIN(parent_capec_ids);
CREATE INDEX ix_capec_child_capec_ids ON ares.capec USING GIN(child_capec_ids);
CREATE INDEX ix_capec_domains ON ares.capec USING GIN(domains);
CREATE INDEX ix_capec_prerequisites ON ares.capec USING GIN(prerequisites);
CREATE INDEX ix_capec_mitigations ON ares.capec USING GIN(mitigations);
