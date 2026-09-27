CREATE TABLE ares.project_rule (
    id             BIGSERIAL PRIMARY KEY,
    project_id     BIGINT NOT NULL REFERENCES ares.project(id) ON DELETE CASCADE,
    rule_type      VARCHAR(50) NOT NULL,
    enabled        BOOLEAN NOT NULL DEFAULT TRUE,
    note           TEXT,
    config         JSONB NOT NULL DEFAULT '{}',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ
);

CREATE INDEX idx_project_rule_project ON ares.project_rule(project_id);
