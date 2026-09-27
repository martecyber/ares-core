SET search_path TO ares, public;

-- Caido API integrations: Ares calls Caido's GraphQL directly (push-based, vs the pull-based plugin).
-- Requires Caido to be reachable from the Ares server (e.g. both on the same VPN).
-- Access token is stored AES-256-GCM encrypted (same as Shodan).

CREATE TABLE caido_api_integration (
    id                       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    label                    VARCHAR(120) NOT NULL UNIQUE,
    base_url                 VARCHAR(500) NOT NULL,
    access_token_ciphertext  BYTEA,
    access_token_iv          BYTEA,
    enabled                  BOOLEAN      NOT NULL DEFAULT TRUE,
    -- unknown | ok | error
    connection_status        VARCHAR(32)  NOT NULL DEFAULT 'unknown',
    last_tested_at           TIMESTAMPTZ,
    -- Caido projects available on this instance; populated by testConnection / listProjects.
    -- Shape: [{"id":"...","name":"..."}]
    known_projects           JSONB        NOT NULL DEFAULT '[]'::jsonb,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Which Ares projects may schedule tasks against this API integration.
CREATE TABLE caido_api_integration_grant (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    integration_id   BIGINT      NOT NULL REFERENCES ares.caido_api_integration(id) ON DELETE CASCADE,
    organization_id  BIGINT      NOT NULL,
    project_id       BIGINT      REFERENCES ares.project(id) ON DELETE CASCADE,
    capabilities     JSONB       NOT NULL DEFAULT '[]'::jsonb,
    active           BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_caido_api_grant_integration ON ares.caido_api_integration_grant(integration_id);
CREATE INDEX ix_caido_api_grant_project     ON ares.caido_api_integration_grant(project_id);

-- Tasks that Ares executes directly against Caido (no plugin poll needed).
CREATE TABLE caido_api_task (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    integration_id     BIGINT       NOT NULL REFERENCES ares.caido_api_integration(id) ON DELETE CASCADE,
    project_id         BIGINT       NOT NULL REFERENCES ares.project(id) ON DELETE CASCADE,
    caido_project_id   VARCHAR(255) NOT NULL,
    caido_project_name VARCHAR(255),
    action             VARCHAR(30)  NOT NULL,
    cron_expression    VARCHAR(120),
    enabled            BOOLEAN      NOT NULL DEFAULT TRUE,
    status             VARCHAR(20)  NOT NULL DEFAULT 'pending',
    result             JSONB,
    error              TEXT,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_run_at        TIMESTAMPTZ,
    next_run_at        TIMESTAMPTZ
);
CREATE INDEX ix_caido_api_task_project     ON ares.caido_api_task(project_id);
CREATE INDEX ix_caido_api_task_integration ON ares.caido_api_task(integration_id);
