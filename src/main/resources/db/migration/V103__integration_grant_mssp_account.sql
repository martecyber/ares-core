-- V103: store the MSSP managed account selected when granting a Tenable MSSP integration.
ALTER TABLE ares.integration_grant ADD COLUMN IF NOT EXISTS account_id   TEXT;
ALTER TABLE ares.integration_grant ADD COLUMN IF NOT EXISTS account_name TEXT;
