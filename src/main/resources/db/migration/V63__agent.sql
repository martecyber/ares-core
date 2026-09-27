-- ares-agent: remote daemon registration + heartbeat tracking.
-- Each agent is org-scoped, authenticates via a long-lived token (SHA-256 hashed),
-- and self-reports installed scan tools (capabilities). Status is derived from last_seen_at.

CREATE TABLE ares.agent (
    id                  BIGSERIAL PRIMARY KEY,
    organization_id     BIGINT       NOT NULL REFERENCES ares.organization(id) ON DELETE CASCADE,
    name                VARCHAR(120) NOT NULL,
    description         TEXT,

    -- Auth: enrollment_code is single-use (cleared after agent calls /enroll).
    -- token_hash is SHA-256 of the long-lived bearer; the plaintext is returned to the agent ONCE.
    enrollment_code     VARCHAR(64) UNIQUE,
    token_hash          CHAR(64)    UNIQUE,

    enabled             BOOLEAN     NOT NULL DEFAULT TRUE,

    -- Self-reported environment info — null until first heartbeat.
    hostname            VARCHAR(255),
    platform            VARCHAR(40),     -- linux | windows | macos
    arch                VARCHAR(20),     -- x86_64 | aarch64 | …
    version             VARCHAR(40),

    -- JSONB array of {tool, path, version, canRoot?} dicts.
    capabilities        JSONB       NOT NULL DEFAULT '[]',

    last_seen_at        TIMESTAMPTZ,
    registered_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    registered_by_user  BIGINT      REFERENCES "user"(id) ON DELETE SET NULL,

    CONSTRAINT uq_agent_org_name UNIQUE (organization_id, name)
);

CREATE INDEX idx_agent_org  ON ares.agent(organization_id);
CREATE INDEX idx_agent_seen ON ares.agent(last_seen_at);
