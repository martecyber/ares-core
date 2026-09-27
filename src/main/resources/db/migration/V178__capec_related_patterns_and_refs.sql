-- Same treatment as CWE's V176: capture every Related_Attack_Pattern Nature (not just
-- ChildOf/ParentOf) and weakness-level URL references (resolved against CAPEC's own
-- <External_References> catalog). Both JSONB, display-only — no AQL query requirement.

ALTER TABLE ares.capec
    ADD COLUMN related_attack_patterns JSONB NOT NULL DEFAULT '[]',
    ADD COLUMN refs                    JSONB NOT NULL DEFAULT '[]';
