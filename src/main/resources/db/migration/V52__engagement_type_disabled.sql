ALTER TABLE ares.engagement_type ADD COLUMN disabled BOOLEAN NOT NULL DEFAULT FALSE;

-- Monitoring is disabled by default and cannot be re-enabled via the UI
UPDATE ares.engagement_type SET disabled = TRUE WHERE code = 'MONITOR';
