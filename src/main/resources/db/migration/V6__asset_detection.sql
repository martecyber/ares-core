SET search_path TO ares, public;

CREATE TABLE detector_tool (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(50) NOT NULL,
    description TEXT
);

CREATE TABLE detector_plugin (
    id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code    VARCHAR(100) NOT NULL,
    tool_id BIGINT NOT NULL REFERENCES detector_tool(id) ON DELETE CASCADE,
    UNIQUE (code, tool_id)
);
CREATE INDEX ix_detector_plugin_tool ON detector_plugin(tool_id);

CREATE TABLE asset (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code            VARCHAR(100) NOT NULL,
    organization_id BIGINT       NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    type            VARCHAR(50)  NOT NULL,
    identifier      VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ,
    metadata        JSONB,
    UNIQUE (organization_id, code)
);
CREATE INDEX ix_asset_org  ON asset(organization_id);
CREATE INDEX ix_asset_type ON asset(type);

CREATE TABLE asset_relationships (
    from_asset_id BIGINT       NOT NULL REFERENCES asset(id) ON DELETE CASCADE,
    to_asset_id   BIGINT       NOT NULL REFERENCES asset(id) ON DELETE CASCADE,
    type          VARCHAR(255) NOT NULL,
    directional   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (from_asset_id, to_asset_id, type)
);

CREATE TABLE engagement_asset_access (
    engagement_id BIGINT NOT NULL REFERENCES engagement(id) ON DELETE CASCADE,
    asset_id      BIGINT NOT NULL REFERENCES asset(id)      ON DELETE CASCADE,
    PRIMARY KEY (engagement_id, asset_id)
);

CREATE TABLE reference_catalog (
    id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code     VARCHAR(20) NOT NULL UNIQUE,
    title    VARCHAR(100) NOT NULL,
    metadata JSONB
);

CREATE TABLE reference_entry (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title       VARCHAR(50) NOT NULL,
    description TEXT,
    catalog_id  BIGINT NOT NULL REFERENCES reference_catalog(id) ON DELETE CASCADE
);
CREATE INDEX ix_re_catalog ON reference_entry(catalog_id);

CREATE TABLE detection (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    engagement_id BIGINT       NOT NULL REFERENCES engagement(id)      ON DELETE CASCADE,
    asset_id      BIGINT       REFERENCES asset(id)                    ON DELETE SET NULL,
    plugin_id     BIGINT       NOT NULL REFERENCES detector_plugin(id),
    severity      VARCHAR(15)  NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'open',
    title         VARCHAR(100) NOT NULL,
    description   TEXT,
    raw_data      JSONB,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_seen     TIMESTAMPTZ,
    UNIQUE (engagement_id, asset_id, plugin_id)
);
CREATE INDEX ix_detection_engagement ON detection(engagement_id);
CREATE INDEX ix_detection_asset      ON detection(asset_id);
CREATE INDEX ix_detection_severity   ON detection(severity);
CREATE INDEX ix_detection_status     ON detection(status);

-- Seed reference catalogs
INSERT INTO reference_catalog (code, title) VALUES
    ('CVE',   'Common Vulnerabilities and Exposures'),
    ('CWE',   'Common Weakness Enumeration'),
    ('OWASP', 'OWASP Top 10'),
    ('ATT&CK','MITRE ATT&CK'),
    ('CAPEC', 'Common Attack Pattern Enumeration and Classification');
