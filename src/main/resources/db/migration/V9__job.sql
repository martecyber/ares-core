SET search_path TO ares, public;

CREATE TABLE job (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type            VARCHAR(50) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'pending',
    organization_id BIGINT      REFERENCES organization(id) ON DELETE SET NULL,
    created_by      BIGINT      REFERENCES "user"(id)       ON DELETE SET NULL,
    payload         JSONB,
    result          JSONB,
    error           TEXT,
    progress        INTEGER     NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ
);
CREATE INDEX ix_job_org    ON job(organization_id);
CREATE INDEX ix_job_status ON job(status);
CREATE INDEX ix_job_type   ON job(type);
