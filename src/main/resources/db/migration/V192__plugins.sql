CREATE TABLE ares.plugin (
    id               BIGSERIAL PRIMARY KEY,
    plugin_id        VARCHAR(100) NOT NULL,
    version          VARCHAR(50)  NOT NULL,
    display_name     VARCHAR(150) NOT NULL,
    vendor           VARCHAR(150),
    license          VARCHAR(150),
    description      TEXT,
    sdk_version      VARCHAR(20)  NOT NULL,
    source           VARCHAR(20)  NOT NULL, -- 'marketplace' | 'upload'
    source_url       TEXT,
    checksum_sha256  VARCHAR(64)  NOT NULL,
    filename         VARCHAR(255) NOT NULL,
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    uninstalled      BOOLEAN      NOT NULL DEFAULT FALSE,
    installed_by     BIGINT,
    installed_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_plugin_plugin_id UNIQUE (plugin_id)
);
