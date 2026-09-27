-- V102: store the last connection-test error message so the UI can display it on hover.
ALTER TABLE ares.integration ADD COLUMN IF NOT EXISTS connection_error TEXT;
ALTER TABLE ares.caido_api_integration ADD COLUMN IF NOT EXISTS connection_error TEXT;
