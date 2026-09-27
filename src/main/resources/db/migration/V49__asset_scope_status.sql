ALTER TABLE ares.engagement_asset_access
    ADD COLUMN scope_status   VARCHAR(20) NOT NULL DEFAULT 'indeterminate',
    ADD COLUMN scope_override BOOLEAN     NOT NULL DEFAULT FALSE;
