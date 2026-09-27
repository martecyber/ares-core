SET search_path TO ares, public;

CREATE TABLE organization (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    slug       VARCHAR(255) NOT NULL UNIQUE,
    status     VARCHAR(10)  NOT NULL DEFAULT 'active',
    settings   JSONB,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_org_slug   ON organization(slug);
CREATE INDEX ix_org_status ON organization(status);
