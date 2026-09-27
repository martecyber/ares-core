SET search_path TO ares, public;

-- Singleton row (id always 1) holding the encrypted VulnCheck KEV API key.
-- Separate from the `integration` table on purpose: that table is grant/capability-based
-- (project-scoped scanning tools), which doesn't fit a platform-wide KB catalog sync.
CREATE TABLE IF NOT EXISTS kb_vulncheck_settings (
    id          SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    api_key     BYTEA,
    api_key_iv  BYTEA,
    updated_at  TIMESTAMPTZ
);
