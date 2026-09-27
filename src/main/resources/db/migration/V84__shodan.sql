SET search_path TO ares, public;

CREATE TABLE shodan_integration (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    label               VARCHAR(120) NOT NULL UNIQUE,
    api_key_ciphertext  BYTEA        NOT NULL,
    api_key_iv          BYTEA        NOT NULL,
    enabled             BOOLEAN      NOT NULL DEFAULT TRUE,
    -- unknown | ok | error
    connection_status   VARCHAR(20)  NOT NULL DEFAULT 'unknown',
    last_tested_at      TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE shodan_task (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    integration_id   BIGINT       NOT NULL REFERENCES ares.shodan_integration(id) ON DELETE CASCADE,
    organization_id  BIGINT       NOT NULL,
    label            VARCHAR(120),
    -- HOST_INFO | SEARCH
    type             VARCHAR(20)  NOT NULL,
    -- IP address (HOST_INFO) or Shodan search query (SEARCH)
    query            TEXT         NOT NULL,
    result_limit     INT          NOT NULL DEFAULT 100,
    -- null = one-shot; non-null = recurring (Spring 6-field or standard 5-field cron)
    cron_expression  VARCHAR(120),
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    -- pending | running | completed | failed
    status           VARCHAR(20)  NOT NULL DEFAULT 'pending',
    assets_created   INT,
    assets_updated   INT,
    hosts_processed  INT,
    error            TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,
    last_run_at      TIMESTAMPTZ,
    next_run_at      TIMESTAMPTZ
);

CREATE INDEX ix_shodan_task_org         ON ares.shodan_task(organization_id);
CREATE INDEX ix_shodan_task_integration ON ares.shodan_task(integration_id);
CREATE INDEX ix_shodan_task_next_run    ON ares.shodan_task(next_run_at)
    WHERE enabled = TRUE AND cron_expression IS NOT NULL AND status <> 'running';
