-- CLI browser-login ("device flow"): `ares configure` opens a browser instead of asking the
-- user to paste an API key. device_code is the real secret exchanged between the CLI and the
-- server (long, random, sent over HTTPS only); user_code is a short human-readable string shown
-- on both the terminal and the browser page purely so the user can visually confirm they're
-- approving the request their own terminal started. api_token is the plaintext of a real
-- user_api_token, stored only transiently between approval and the CLI's next poll, then cleared.

CREATE TABLE ares.cli_device_auth (
    id              BIGSERIAL PRIMARY KEY,
    device_code     VARCHAR(64) NOT NULL UNIQUE,
    user_code       VARCHAR(16) NOT NULL,
    client_info     VARCHAR(255),
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING | APPROVED | DENIED | CONSUMED | EXPIRED
    user_id         BIGINT REFERENCES "user"(id) ON DELETE SET NULL,
    api_token       VARCHAR(128),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_cli_device_auth_expires ON ares.cli_device_auth(expires_at);
