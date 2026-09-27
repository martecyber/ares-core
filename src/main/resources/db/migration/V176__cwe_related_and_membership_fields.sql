-- Extends ares.cwe with fields the MITRE XML already carries but the Phase 5 parser discarded:
-- the full Related_Weakness relationship set (not just ChildOf/ParentOf), bibliographic URL
-- references, Category/View memberships (inverted from Has_Member edges), notes, alternate
-- terms and detection methods. All JSONB display-only data, same precedent as
-- consequences/mitigations/vulnerability_mapping — no AQL query requirement.

ALTER TABLE ares.cwe
    ADD COLUMN related_weaknesses JSONB NOT NULL DEFAULT '[]',
    ADD COLUMN refs               JSONB NOT NULL DEFAULT '[]',
    ADD COLUMN memberships        JSONB NOT NULL DEFAULT '[]',
    ADD COLUMN notes              JSONB NOT NULL DEFAULT '[]',
    ADD COLUMN alternate_terms    JSONB NOT NULL DEFAULT '[]',
    ADD COLUMN detection_methods  JSONB NOT NULL DEFAULT '[]';
