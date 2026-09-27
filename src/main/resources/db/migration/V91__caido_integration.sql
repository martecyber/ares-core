SET search_path TO ares, public;

-- A Caido connection. Unlike Shodan, Ares does NOT call Caido (Caido is self-hosted and
-- unreachable from the server). Instead a Caido plugin authenticates to Ares with a token;
-- we store only its SHA-256 hash. connection_status/last_seen_at are driven by plugin heartbeats.
CREATE TABLE caido_integration (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    label              VARCHAR(120) NOT NULL UNIQUE,
    token_hash         VARCHAR(64)  NOT NULL UNIQUE,
    enabled            BOOLEAN      NOT NULL DEFAULT TRUE,
    -- unknown | ok  (ok once a plugin has checked in)
    connection_status  VARCHAR(20)  NOT NULL DEFAULT 'unknown',
    last_seen_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Links an Ares project to a Caido project (string id reported by the plugin), through a
-- specific Caido integration. The plugin resolves the Ares project by passing its current
-- caido_project_id; we map it here.
CREATE TABLE caido_project_link (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id          BIGINT       NOT NULL REFERENCES ares.project(id) ON DELETE CASCADE,
    integration_id      BIGINT       NOT NULL REFERENCES ares.caido_integration(id) ON DELETE CASCADE,
    caido_project_id    VARCHAR(255) NOT NULL,
    caido_project_name  VARCHAR(255),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    -- one Caido project maps to at most one Ares project per integration
    UNIQUE (integration_id, caido_project_id)
);

CREATE INDEX ix_caido_project_link_project     ON ares.caido_project_link(project_id);
CREATE INDEX ix_caido_project_link_integration ON ares.caido_project_link(integration_id);
