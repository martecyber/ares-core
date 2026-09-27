SET search_path TO ares, public;

CREATE TABLE audit_log (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id      BIGINT       REFERENCES "user"(id)       ON DELETE SET NULL,
    organization_id BIGINT     REFERENCES organization(id) ON DELETE SET NULL,
    action        VARCHAR(100) NOT NULL,
    resource_type VARCHAR(50)  NOT NULL,
    resource_id   BIGINT,
    old_value     JSONB,
    new_value     JSONB,
    ip_address    TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_audit_actor    ON audit_log(actor_id);
CREATE INDEX ix_audit_org      ON audit_log(organization_id);
CREATE INDEX ix_audit_resource ON audit_log(resource_type, resource_id);
CREATE INDEX ix_audit_created  ON audit_log(created_at DESC);
