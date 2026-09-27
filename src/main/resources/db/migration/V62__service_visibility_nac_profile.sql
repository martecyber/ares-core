-- Extend service_visibility with an optional NAC profile (free-text tag).
-- A vantage point is now (source_ip, nac_profile), so the same IP can produce
-- different visibility records depending on which NAC profile the operator is
-- authenticated under. Empty string represents "no NAC profile" — NOT NULL
-- avoids PostgreSQL's "NULL is distinct" quirk that would break unique upserts.

ALTER TABLE ares.service_visibility
    ADD COLUMN nac_profile VARCHAR(64) NOT NULL DEFAULT '';

ALTER TABLE ares.service_visibility
    DROP CONSTRAINT uq_service_visibility;

ALTER TABLE ares.service_visibility
    ADD CONSTRAINT uq_service_visibility UNIQUE (service_asset_id, source_ip, nac_profile);

-- Audit column on scan_import — what NAC profile the operator declared (if any).
ALTER TABLE ares.scan_import ADD COLUMN nac_profile VARCHAR(64);
