SET search_path TO ares, public;

CREATE TABLE integration (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tool_id         BIGINT      NOT NULL REFERENCES detector_tool(id),
    name            VARCHAR(50) NOT NULL,
    type            VARCHAR(50) NOT NULL,
    organization_id BIGINT      REFERENCES organization(id) ON DELETE CASCADE,
    status          VARCHAR(20) NOT NULL DEFAULT 'active',
    settings        JSONB,
    data            JSONB,
    credentials     BYTEA,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_integration_org  ON integration(organization_id);
CREATE INDEX ix_integration_tool ON integration(tool_id);
