SET search_path TO ares, public;

-- Mark findings as ready to include in the next report
ALTER TABLE finding ADD COLUMN is_ready_to_report BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE finding ADD COLUMN reported_at TIMESTAMPTZ;

CREATE INDEX ix_finding_ready_to_report ON finding(engagement_id, is_ready_to_report)
    WHERE is_ready_to_report = TRUE;

-- Report templates (DOCX, future: LaTeX)
CREATE TABLE report_template (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name              VARCHAR(200) NOT NULL,
    description       TEXT,
    format            VARCHAR(20)  NOT NULL DEFAULT 'docx',
    is_generic        BOOLEAN      NOT NULL DEFAULT FALSE,
    is_active         BOOLEAN      NOT NULL DEFAULT TRUE,
    bucket            VARCHAR(100) NOT NULL,
    object_key        VARCHAR(500) NOT NULL,
    original_filename VARCHAR(500) NOT NULL,
    created_by        BIGINT       REFERENCES "user"(id) ON DELETE SET NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Which engagement types each template applies to (empty = all / generic)
CREATE TABLE report_template_engagement_type (
    template_id         BIGINT NOT NULL REFERENCES report_template(id) ON DELETE CASCADE,
    engagement_type_id  BIGINT NOT NULL REFERENCES engagement_type(id)  ON DELETE CASCADE,
    PRIMARY KEY (template_id, engagement_type_id)
);

-- Variable name → field mapping for template placeholders like {{variable_name}}
CREATE TABLE report_template_variable (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    template_id          BIGINT       NOT NULL REFERENCES report_template(id) ON DELETE CASCADE,
    variable_name        VARCHAR(100) NOT NULL,
    source_type          VARCHAR(30)  NOT NULL,   -- 'system' | 'field_type'
    system_field         VARCHAR(100),             -- e.g. 'finding.title', 'finding.severity', 'engagement.name'
    field_type_id        BIGINT       REFERENCES finding_field_type(id) ON DELETE SET NULL,
    UNIQUE (template_id, variable_name)
);

-- Link report to the template that was used; store the generated file location
ALTER TABLE report ADD COLUMN template_id BIGINT REFERENCES report_template(id) ON DELETE SET NULL;
ALTER TABLE report ADD COLUMN report_bucket VARCHAR(100);
ALTER TABLE report ADD COLUMN report_object_key VARCHAR(500);

CREATE INDEX ix_report_template ON report(template_id);
CREATE INDEX ix_report_template_var ON report_template_variable(template_id);
