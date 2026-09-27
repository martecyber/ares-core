SET search_path TO ares, public;

CREATE TABLE engagement_type (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name         VARCHAR(50) NOT NULL,
    code         VARCHAR(20) NOT NULL UNIQUE,
    supertype_id BIGINT REFERENCES engagement_type(id)
);

CREATE TABLE engagement_status (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name         VARCHAR(50) NOT NULL UNIQUE,
    description  TEXT,
    means_closed BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE engagement_status_transition (
    from_status_id BIGINT      NOT NULL REFERENCES engagement_status(id) ON DELETE CASCADE,
    to_status_id   BIGINT      NOT NULL REFERENCES engagement_status(id) ON DELETE CASCADE,
    name           VARCHAR(50),
    description    TEXT,
    PRIMARY KEY (from_status_id, to_status_id)
);

CREATE TABLE engagement (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id BIGINT      NOT NULL REFERENCES organization(id)    ON DELETE CASCADE,
    name            VARCHAR(50) NOT NULL,
    type_id         BIGINT      REFERENCES engagement_type(id),
    status_id       BIGINT      NOT NULL REFERENCES engagement_status(id),
    start_date      TIMESTAMPTZ,
    end_date        TIMESTAMPTZ,
    owner_user_id   BIGINT      REFERENCES "user"(id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_engagement_org    ON engagement(organization_id);
CREATE INDEX ix_engagement_status ON engagement(status_id);

CREATE TABLE engagement_member (
    engagement_id BIGINT      NOT NULL REFERENCES engagement(id) ON DELETE CASCADE,
    user_id       BIGINT      NOT NULL REFERENCES "user"(id)     ON DELETE CASCADE,
    role          VARCHAR(255),
    added_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (engagement_id, user_id)
);

CREATE TABLE engagement_scope_entry (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    engagement_id BIGINT       NOT NULL REFERENCES engagement(id) ON DELETE CASCADE,
    kind          VARCHAR(50)  NOT NULL,
    value         VARCHAR(255) NOT NULL,
    notes         TEXT,
    metadata      JSONB,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_ese_engagement ON engagement_scope_entry(engagement_id);

CREATE TABLE engagement_status_history (
    engagement_id BIGINT      NOT NULL REFERENCES engagement(id)        ON DELETE CASCADE,
    status_id     BIGINT      NOT NULL REFERENCES engagement_status(id),
    changed_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (engagement_id, changed_at)
);

-- Seed default engagement statuses
INSERT INTO engagement_status (name, description, means_closed) VALUES
    ('draft',     'Not yet started',              FALSE),
    ('active',    'Currently in progress',        FALSE),
    ('completed', 'Finished successfully',        TRUE),
    ('archived',  'Archived, no longer active',   TRUE);

INSERT INTO engagement_status_transition (from_status_id, to_status_id) VALUES
    ((SELECT id FROM engagement_status WHERE name='draft'),     (SELECT id FROM engagement_status WHERE name='active')),
    ((SELECT id FROM engagement_status WHERE name='active'),    (SELECT id FROM engagement_status WHERE name='completed')),
    ((SELECT id FROM engagement_status WHERE name='active'),    (SELECT id FROM engagement_status WHERE name='archived')),
    ((SELECT id FROM engagement_status WHERE name='completed'), (SELECT id FROM engagement_status WHERE name='archived'));
