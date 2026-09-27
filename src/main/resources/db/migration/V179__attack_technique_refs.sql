-- Same treatment as CWE's/CAPEC's References card: capture every STIX external_references entry
-- with a URL (the technique's own attack.mitre.org page plus every inline citation the
-- description references) — previously discarded entirely. JSONB, display-only.

ALTER TABLE ares.attack_technique
    ADD COLUMN refs JSONB NOT NULL DEFAULT '[]';
