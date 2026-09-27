-- ATT&CK moves from three MongoDB collections (kb_attack_tactics/kb_attack_techniques/
-- kb_attack_mitigations) to Postgres (AQL-wide initiative, Phase 5 — final entity of this phase).
--
-- attack_technique.tactics stays a native text[] (HAS-queryable) for API/frontend compatibility —
-- ares-ui reads it directly (KbAttackView.vue's matrix-by-tactic grouping, technique detail view)
-- and it must keep holding tactic SHORT NAMES exactly as before, not become a relation-only field.
--
-- attack_technique_tactic and attack_technique_mitigation are NEW real join tables (this phase's
-- confirmed-in-scope work): the former normalizes the same tactic short-name/matrix matching the
-- text[] column already encodes (both stay in sync, populated by the same sync pass); the latter
-- is genuinely new data — AttackStixParser previously discarded STIX "relationship" objects
-- (relationship_type="mitigates") entirely. Real FK constraints are safe here (unlike KEV's
-- deliberately FK-less cve_id) because tactics/techniques/mitigations/relationships all come from
-- the SAME STIX bundle in the SAME sync pass — no cross-source timing gap.

CREATE TABLE ares.attack_tactic (
    id          BIGSERIAL PRIMARY KEY,
    stix_id     TEXT,
    attack_id   TEXT NOT NULL,
    name        TEXT,
    description TEXT,
    short_name  TEXT,
    matrix      TEXT NOT NULL,
    sort_order  INTEGER,
    synced_at   TIMESTAMPTZ
);
CREATE UNIQUE INDEX ux_attack_tactic_attackid_matrix ON ares.attack_tactic(attack_id, matrix);
CREATE INDEX ix_attack_tactic_matrix ON ares.attack_tactic(matrix);
CREATE INDEX ix_attack_tactic_short_name ON ares.attack_tactic(short_name);

CREATE TABLE ares.attack_technique (
    id                    BIGSERIAL PRIMARY KEY,
    stix_id               TEXT,
    attack_id             TEXT NOT NULL,
    name                  TEXT,
    description           TEXT,
    matrix                TEXT NOT NULL,
    is_subtechnique       BOOLEAN NOT NULL DEFAULT FALSE,
    tactics               TEXT[] NOT NULL DEFAULT '{}',
    platforms             TEXT[] NOT NULL DEFAULT '{}',
    data_sources          TEXT[] NOT NULL DEFAULT '{}',
    detection             TEXT,
    permissions_required  TEXT[] NOT NULL DEFAULT '{}',
    deprecated            BOOLEAN NOT NULL DEFAULT FALSE,
    revoked               BOOLEAN NOT NULL DEFAULT FALSE,
    synced_at             TIMESTAMPTZ
);
CREATE UNIQUE INDEX ux_attack_technique_attackid_matrix ON ares.attack_technique(attack_id, matrix);
CREATE INDEX ix_attack_technique_matrix ON ares.attack_technique(matrix);
CREATE INDEX ix_attack_technique_is_subtechnique ON ares.attack_technique(is_subtechnique);
CREATE INDEX ix_attack_technique_tactics ON ares.attack_technique USING GIN(tactics);
CREATE INDEX ix_attack_technique_platforms ON ares.attack_technique USING GIN(platforms);
CREATE INDEX ix_attack_technique_data_sources ON ares.attack_technique USING GIN(data_sources);
CREATE INDEX ix_attack_technique_permissions_required ON ares.attack_technique USING GIN(permissions_required);

CREATE TABLE ares.attack_mitigation (
    id          BIGSERIAL PRIMARY KEY,
    stix_id     TEXT,
    attack_id   TEXT NOT NULL,
    name        TEXT,
    description TEXT,
    matrix      TEXT NOT NULL,
    deprecated  BOOLEAN NOT NULL DEFAULT FALSE,
    synced_at   TIMESTAMPTZ
);
CREATE UNIQUE INDEX ux_attack_mitigation_attackid_matrix ON ares.attack_mitigation(attack_id, matrix);
CREATE INDEX ix_attack_mitigation_matrix ON ares.attack_mitigation(matrix);

CREATE TABLE ares.attack_technique_tactic (
    technique_id BIGINT NOT NULL REFERENCES ares.attack_technique(id) ON DELETE CASCADE,
    tactic_id    BIGINT NOT NULL REFERENCES ares.attack_tactic(id) ON DELETE CASCADE,
    PRIMARY KEY (technique_id, tactic_id)
);
CREATE INDEX ix_attack_technique_tactic_tactic ON ares.attack_technique_tactic(tactic_id);

CREATE TABLE ares.attack_technique_mitigation (
    technique_id  BIGINT NOT NULL REFERENCES ares.attack_technique(id) ON DELETE CASCADE,
    mitigation_id BIGINT NOT NULL REFERENCES ares.attack_mitigation(id) ON DELETE CASCADE,
    PRIMARY KEY (technique_id, mitigation_id)
);
CREATE INDEX ix_attack_technique_mitigation_mitigation ON ares.attack_technique_mitigation(mitigation_id);
