SET search_path TO ares, public;

-- Make tool_id nullable: Tool Integrations (Tenable, Qualys…) are not linked to detector_tool
ALTER TABLE integration ALTER COLUMN tool_id DROP NOT NULL;

-- Add scope: PLATFORM (MSSP-owned, shareable) | ORGANIZATION (org-specific)
ALTER TABLE integration ADD COLUMN scope VARCHAR(20) NOT NULL DEFAULT 'PLATFORM';

-- IV for AES-256-GCM credential encryption
ALTER TABLE integration ADD COLUMN credential_iv BYTEA;

-- Connection tracking
ALTER TABLE integration ADD COLUMN last_sync_at      TIMESTAMPTZ;
ALTER TABLE integration ADD COLUMN connection_status VARCHAR(20) NOT NULL DEFAULT 'unknown';

-- Grant table: platform integration → org (org_wide when engagement_id IS NULL)
--              or org integration → specific engagement
CREATE TABLE integration_grant (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    integration_id  BIGINT   NOT NULL REFERENCES integration(id) ON DELETE CASCADE,
    organization_id BIGINT   NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    engagement_id   BIGINT   REFERENCES engagement(id) ON DELETE CASCADE,
    capabilities    JSONB    NOT NULL DEFAULT '[]',
    active          BOOLEAN  NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_integration_grant_integration ON integration_grant(integration_id);
CREATE INDEX ix_integration_grant_org         ON integration_grant(organization_id);
CREATE INDEX ix_integration_grant_engagement  ON integration_grant(engagement_id);
