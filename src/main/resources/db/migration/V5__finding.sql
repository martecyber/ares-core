SET search_path TO ares, public;

CREATE TABLE finding_status (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name         VARCHAR(50) NOT NULL UNIQUE,
    description  TEXT,
    means_closed BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE finding_status_transition (
    from_status_id BIGINT NOT NULL REFERENCES finding_status(id) ON DELETE CASCADE,
    to_status_id   BIGINT NOT NULL REFERENCES finding_status(id) ON DELETE CASCADE,
    name           VARCHAR(50),
    description    TEXT,
    PRIMARY KEY (from_status_id, to_status_id)
);

CREATE TABLE finding_field_type (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title       VARCHAR(50) NOT NULL,
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE finding_score_type (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title       VARCHAR(50) NOT NULL,
    description TEXT
);

CREATE TABLE finding_template (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    severity   VARCHAR(15) NOT NULL,
    title      VARCHAR(100) NOT NULL,
    creator_id BIGINT REFERENCES "user"(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE finding_template_field (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    template_id BIGINT NOT NULL REFERENCES finding_template(id)   ON DELETE CASCADE,
    type_id     BIGINT NOT NULL REFERENCES finding_field_type(id),
    field_text  TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE finding_template_score (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    template_id BIGINT         NOT NULL REFERENCES finding_template(id)  ON DELETE CASCADE,
    type_id     BIGINT         NOT NULL REFERENCES finding_score_type(id),
    score       NUMERIC(3,1)   NOT NULL,
    metadata    JSONB,
    created_at  TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

CREATE TABLE finding (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    engagement_id BIGINT       NOT NULL REFERENCES engagement(id) ON DELETE CASCADE,
    severity      VARCHAR(15)  NOT NULL,
    title         VARCHAR(100) NOT NULL,
    status_id     BIGINT       NOT NULL REFERENCES finding_status(id),
    creator_id    BIGINT       REFERENCES "user"(id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_finding_engagement ON finding(engagement_id);
CREATE INDEX ix_finding_status     ON finding(status_id);
CREATE INDEX ix_finding_severity   ON finding(severity);

CREATE TABLE finding_status_history (
    finding_id       BIGINT      NOT NULL REFERENCES finding(id)        ON DELETE CASCADE,
    finding_status_id BIGINT     NOT NULL REFERENCES finding_status(id),
    changed_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (finding_id, changed_at)
);

CREATE TABLE finding_field (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    finding_id BIGINT NOT NULL REFERENCES finding(id)          ON DELETE CASCADE,
    type_id    BIGINT NOT NULL REFERENCES finding_field_type(id),
    field_text TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_finding_field_finding ON finding_field(finding_id);

CREATE TABLE finding_score (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    finding_id BIGINT       NOT NULL REFERENCES finding(id)          ON DELETE CASCADE,
    type_id    BIGINT       NOT NULL REFERENCES finding_score_type(id),
    score      NUMERIC(3,1) NOT NULL,
    metadata   JSONB,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_finding_score_finding ON finding_score(finding_id);

-- Seed finding statuses
INSERT INTO finding_status (name, description, means_closed) VALUES
    ('open',           'Newly created, pending triage',  FALSE),
    ('in_review',      'Under analysis by the team',     FALSE),
    ('resolved',       'Fix confirmed and verified',     TRUE),
    ('accepted_risk',  'Risk accepted by client',        TRUE),
    ('false_positive', 'Confirmed not a real issue',     TRUE);

INSERT INTO finding_status_transition (from_status_id, to_status_id) VALUES
    ((SELECT id FROM finding_status WHERE name='open'),          (SELECT id FROM finding_status WHERE name='in_review')),
    ((SELECT id FROM finding_status WHERE name='open'),          (SELECT id FROM finding_status WHERE name='false_positive')),
    ((SELECT id FROM finding_status WHERE name='in_review'),     (SELECT id FROM finding_status WHERE name='resolved')),
    ((SELECT id FROM finding_status WHERE name='in_review'),     (SELECT id FROM finding_status WHERE name='accepted_risk')),
    ((SELECT id FROM finding_status WHERE name='in_review'),     (SELECT id FROM finding_status WHERE name='false_positive')),
    ((SELECT id FROM finding_status WHERE name='resolved'),      (SELECT id FROM finding_status WHERE name='open')),
    ((SELECT id FROM finding_status WHERE name='accepted_risk'), (SELECT id FROM finding_status WHERE name='open'));

-- Seed default score types
INSERT INTO finding_score_type (title, description) VALUES
    ('CVSS 3.1', 'Common Vulnerability Scoring System v3.1'),
    ('CVSS 4.0', 'Common Vulnerability Scoring System v4.0');

-- Seed default field types
INSERT INTO finding_field_type (title, description) VALUES
    ('Description',          'Full technical description of the finding'),
    ('Steps to Reproduce',   'Step-by-step reproduction instructions'),
    ('Impact',               'Business and technical impact'),
    ('Remediation',          'Recommended fix or mitigation'),
    ('Evidence',             'Supporting evidence (text reference to attached files)'),
    ('CVSS Vector',          'Raw CVSS vector string');
