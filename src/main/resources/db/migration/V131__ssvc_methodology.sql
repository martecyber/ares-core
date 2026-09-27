-- Generic, user-extensible SSVC methodology engine. A methodology (e.g. "SSVCv2",
-- "CISAv1", or a user-defined one) has one or more roles (e.g. Deployer, Supplier).
-- Each role is a recursive decision tree: a branch node asks one question (a
-- decision point) via its options; each option leads to exactly one child node, down
-- to leaf nodes carrying the outcome. System methodologies/roles (is_system = TRUE)
-- are immutable — seeded by V132, never editable/deletable via the API.

CREATE TABLE ares.ssvc_methodology (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(50) NOT NULL UNIQUE,
    name        VARCHAR(150) NOT NULL,
    description TEXT,
    is_system   BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE ares.ssvc_role (
    id                     BIGSERIAL PRIMARY KEY,
    methodology_id         BIGINT NOT NULL REFERENCES ares.ssvc_methodology(id) ON DELETE CASCADE,
    code                   VARCHAR(50) NOT NULL,
    name                   VARCHAR(150) NOT NULL,
    description            TEXT,
    uses_priority_mapping  BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order             INT NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (methodology_id, code)
);

-- Self-referencing tree. parent_node_id IS NULL for the root node of a role's tree.
-- parent_option_code identifies which option of the PARENT branch node leads here
-- (NULL for the root). node_type is 'branch' (asks a question via its options, see
-- ssvc_tree_node_option) or 'leaf' (terminal outcome).
CREATE TABLE ares.ssvc_tree_node (
    id                    BIGSERIAL PRIMARY KEY,
    role_id               BIGINT NOT NULL REFERENCES ares.ssvc_role(id) ON DELETE CASCADE,
    parent_node_id        BIGINT REFERENCES ares.ssvc_tree_node(id) ON DELETE CASCADE,
    parent_option_code    VARCHAR(50),
    node_type             VARCHAR(10) NOT NULL CHECK (node_type IN ('branch', 'leaf')),
    -- branch-only
    decision_point_code   VARCHAR(50),
    decision_point_name   VARCHAR(150),
    decision_point_help   TEXT,
    -- leaf-only
    outcome_code          VARCHAR(50),
    outcome_label         VARCHAR(150),
    priority_level        VARCHAR(2) CHECK (priority_level IN ('P0', 'P1', 'P2', 'P3', 'P4')),
    sort_order             INT NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ssvc_tree_node_role ON ares.ssvc_tree_node(role_id);
CREATE INDEX idx_ssvc_tree_node_parent ON ares.ssvc_tree_node(parent_node_id);

CREATE TABLE ares.ssvc_tree_node_option (
    id            BIGSERIAL PRIMARY KEY,
    tree_node_id  BIGINT NOT NULL REFERENCES ares.ssvc_tree_node(id) ON DELETE CASCADE,
    code          VARCHAR(50) NOT NULL,
    label         VARCHAR(150) NOT NULL,
    help_text     TEXT,
    sort_order    INT NOT NULL DEFAULT 0,
    UNIQUE (tree_node_id, code)
);

CREATE INDEX idx_ssvc_tree_node_option_node ON ares.ssvc_tree_node_option(tree_node_id);

-- Authoritative link from a persisted score to the exact leaf node it resolved to.
-- Nullable: only populated for scores of the (generic) "SSVC" finding_score_type.
ALTER TABLE ares.finding_score
    ADD COLUMN ssvc_leaf_node_id BIGINT REFERENCES ares.ssvc_tree_node(id);

ALTER TABLE ares.finding_template_score
    ADD COLUMN ssvc_leaf_node_id BIGINT REFERENCES ares.ssvc_tree_node(id);
