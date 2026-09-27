SET search_path TO ares, public;

CREATE TABLE file (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id BIGINT       NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    engagement_id   BIGINT       REFERENCES engagement(id)            ON DELETE SET NULL,
    finding_id      BIGINT       REFERENCES finding(id)               ON DELETE SET NULL,
    original_name   VARCHAR(500) NOT NULL,
    content_type    VARCHAR(255) NOT NULL,
    size_bytes      BIGINT       NOT NULL,
    bucket          VARCHAR(100) NOT NULL,
    object_key      VARCHAR(500) NOT NULL,
    uploaded_by     BIGINT       REFERENCES "user"(id) ON DELETE SET NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_file_org        ON file(organization_id);
CREATE INDEX ix_file_engagement ON file(engagement_id);
CREATE INDEX ix_file_finding    ON file(finding_id);

CREATE TABLE report (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id BIGINT       NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    engagement_id   BIGINT       REFERENCES engagement(id)            ON DELETE SET NULL,
    type            VARCHAR(30)  NOT NULL,
    format          VARCHAR(20)  NOT NULL DEFAULT 'pdf',
    status          VARCHAR(20)  NOT NULL DEFAULT 'pending',
    title           VARCHAR(255) NOT NULL,
    file_id         BIGINT       REFERENCES file(id)    ON DELETE SET NULL,
    generated_by    BIGINT       REFERENCES "user"(id)  ON DELETE SET NULL,
    error           TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMPTZ
);
CREATE INDEX ix_report_org        ON report(organization_id);
CREATE INDEX ix_report_engagement ON report(engagement_id);
CREATE INDEX ix_report_status     ON report(status);
