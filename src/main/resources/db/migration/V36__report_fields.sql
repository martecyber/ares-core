SET search_path TO ares, public;

-- ── Report field types (Executive Summary, Technical Summary, etc.) ────────────
CREATE TABLE report_field_type (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(100) NOT NULL UNIQUE,   -- slug: executive_summary
    label       VARCHAR(200) NOT NULL,           -- display label
    description TEXT,
    sort_order  INT          NOT NULL DEFAULT 0,
    is_required BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Seed default field types
INSERT INTO report_field_type (name, label, sort_order) VALUES
    ('executive_summary', 'Executive Summary', 1),
    ('technical_summary', 'Technical Summary', 2);

-- ── Reusable content templates per field type ─────────────────────────────────
CREATE TABLE report_field_template (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    field_type_id BIGINT       NOT NULL REFERENCES report_field_type(id) ON DELETE CASCADE,
    name          VARCHAR(200) NOT NULL,
    content       TEXT         NOT NULL DEFAULT '',
    is_default    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX ON report_field_template(field_type_id);

-- ── Findings selected for a report (user-chosen subset) ──────────────────────
CREATE TABLE report_finding (
    report_id  BIGINT NOT NULL REFERENCES report(id) ON DELETE CASCADE,
    finding_id BIGINT NOT NULL REFERENCES finding(id) ON DELETE CASCADE,
    PRIMARY KEY (report_id, finding_id)
);
CREATE INDEX ON report_finding(report_id);

-- ── Custom field content filled during report creation ────────────────────────
CREATE TABLE report_field (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    report_id     BIGINT       NOT NULL REFERENCES report(id) ON DELETE CASCADE,
    field_type_id BIGINT       REFERENCES report_field_type(id) ON DELETE SET NULL,
    field_name    VARCHAR(100) NOT NULL,
    content       TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX ON report_field(report_id);

-- ── Publish workflow ─────────────────────────────────────────────────────────
ALTER TABLE report ADD COLUMN IF NOT EXISTS published_at TIMESTAMPTZ;
