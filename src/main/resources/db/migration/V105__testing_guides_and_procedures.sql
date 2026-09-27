SET search_path TO ares, public;

-- ── Testing Guides (KB) ──────────────────────────────────────────────────────
-- Reusable checklists of tests. Populated by users — no seed data.

CREATE TABLE ares.testing_guide (
    id          BIGSERIAL    PRIMARY KEY,
    name        VARCHAR(160) NOT NULL,
    description TEXT,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    creator_id  BIGINT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE ares.testing_guide_point (
    id          BIGSERIAL    PRIMARY KEY,
    guide_id    BIGINT       NOT NULL REFERENCES ares.testing_guide(id) ON DELETE CASCADE,
    title       VARCHAR(300) NOT NULL,
    description TEXT,
    sort_order  INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_testing_guide_point_guide ON ares.testing_guide_point(guide_id);

-- ── Testing Procedures (KB) ──────────────────────────────────────────────────
-- Rich-text how-to articles. `content` holds HTML (images embedded as data URIs).

CREATE TABLE ares.testing_procedure (
    id         BIGSERIAL    PRIMARY KEY,
    title      VARCHAR(300) NOT NULL,
    content    TEXT,
    creator_id BIGINT,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Procedure ↔ testing-guide-point links (many-to-many)
CREATE TABLE ares.testing_procedure_guide_point (
    procedure_id   BIGINT NOT NULL REFERENCES ares.testing_procedure(id) ON DELETE CASCADE,
    guide_point_id BIGINT NOT NULL REFERENCES ares.testing_guide_point(id) ON DELETE CASCADE,
    PRIMARY KEY (procedure_id, guide_point_id)
);
CREATE INDEX ix_tp_guide_point_point ON ares.testing_procedure_guide_point(guide_point_id);

-- Procedure ↔ external-database entry links (polymorphic: cve|cwe|capec|attack|owasp)
CREATE TABLE ares.testing_procedure_external_ref (
    id           BIGSERIAL    PRIMARY KEY,
    procedure_id BIGINT       NOT NULL REFERENCES ares.testing_procedure(id) ON DELETE CASCADE,
    ref_type     VARCHAR(20)  NOT NULL,
    ref_key      VARCHAR(120) NOT NULL,
    ref_label    VARCHAR(300)
);
CREATE INDEX ix_tp_external_ref_procedure ON ares.testing_procedure_external_ref(procedure_id);

-- ── Project-assigned checklists ──────────────────────────────────────────────
-- A guide assigned to an assessment project. Points are snapshotted so the audit
-- record is stable; guide_id / guide_point_id kept nullable for procedures + re-sync.

CREATE TABLE ares.project_testing_guide (
    id          BIGSERIAL    PRIMARY KEY,
    project_id  BIGINT       NOT NULL REFERENCES ares.project(id) ON DELETE CASCADE,
    guide_id    BIGINT       REFERENCES ares.testing_guide(id) ON DELETE SET NULL,
    name        VARCHAR(160) NOT NULL,
    assigned_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_project_testing_guide_project ON ares.project_testing_guide(project_id);

CREATE TABLE ares.project_testing_guide_item (
    id                       BIGSERIAL    PRIMARY KEY,
    project_testing_guide_id BIGINT       NOT NULL REFERENCES ares.project_testing_guide(id) ON DELETE CASCADE,
    guide_point_id           BIGINT       REFERENCES ares.testing_guide_point(id) ON DELETE SET NULL,
    title                    VARCHAR(300) NOT NULL,
    description              TEXT,
    sort_order               INT          NOT NULL DEFAULT 0,
    status                   VARCHAR(20)  NOT NULL DEFAULT 'pending'
                                          CHECK (status IN ('pending','done','not_applicable')),
    notes                    TEXT,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by               BIGINT
);
CREATE INDEX ix_project_testing_guide_item_ptg ON ares.project_testing_guide_item(project_testing_guide_id);
