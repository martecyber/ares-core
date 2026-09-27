SET search_path TO ares, public;

CREATE TABLE role (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        VARCHAR(100) NOT NULL UNIQUE,
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    is_system   BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE permission (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code        VARCHAR(100) NOT NULL UNIQUE,
    description TEXT
);

CREATE TABLE roles_permission (
    role_id       BIGINT NOT NULL REFERENCES role(id)       ON DELETE CASCADE,
    permission_id BIGINT NOT NULL REFERENCES permission(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE user_role (
    user_id         BIGINT NOT NULL REFERENCES "user"(id) ON DELETE CASCADE,
    role_id         BIGINT NOT NULL REFERENCES role(id)   ON DELETE CASCADE,
    organization_id BIGINT,
    PRIMARY KEY (user_id, role_id, organization_id)
);
CREATE INDEX ix_user_role_user ON user_role(user_id);
CREATE INDEX ix_user_role_org  ON user_role(organization_id);

-- Seed default roles
INSERT INTO role (code, name, description, is_system) VALUES
    ('MSSP_ADMIN',    'MSSP Administrator', 'Full platform access',               TRUE),
    ('MSSP_OPERATOR', 'MSSP Operator',      'Operational access, no admin tasks', TRUE),
    ('CLIENT_ADMIN',  'Client Admin',       'Full access within their org',       TRUE),
    ('CLIENT_USER',   'Client User',        'Read-only access within their org',  TRUE);
