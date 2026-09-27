SET search_path TO ares, public;

CREATE TABLE "user" (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email          VARCHAR(255) NOT NULL UNIQUE,
    password_hash  BYTEA,
    display_name   VARCHAR(255) NOT NULL,
    status         VARCHAR(10)  NOT NULL DEFAULT 'active',
    mfa_enforced   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE user_refresh_token (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id         BIGINT      NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
    token_hash      BYTEA       NOT NULL,
    issued_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ NOT NULL,
    revoked_at      TIMESTAMPTZ,
    rotated_from_id BIGINT      REFERENCES user_refresh_token(id)
);
CREATE INDEX ix_urt_user ON user_refresh_token(user_id);

CREATE TABLE user_mfa_device (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id           BIGINT      NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
    type              VARCHAR(20) NOT NULL,
    secret_ciphertext BYTEA,
    label             VARCHAR(50),
    last_used         TIMESTAMPTZ
);
CREATE INDEX ix_umd_user ON user_mfa_device(user_id);

CREATE TABLE user_api_token (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      BIGINT       NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
    name         VARCHAR(50)  NOT NULL,
    token_hash   VARCHAR(255) NOT NULL UNIQUE,
    scopes       JSONB        NOT NULL DEFAULT '[]',
    expires_at   TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_uat_user ON user_api_token(user_id);
