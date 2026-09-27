-- Tracks which rules were auto-populated from an external bug bounty platform's rules of
-- engagement (e.g. Intigriti's required User-Agent / request header), as opposed to rules
-- created manually by a user — so a re-sync can safely update/remove its own rows without
-- ever touching a manually-created rule of the same type.
ALTER TABLE ares.project_rule ADD COLUMN synced_from VARCHAR(20);
