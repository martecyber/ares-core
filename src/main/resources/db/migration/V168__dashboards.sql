-- Dashboards remodel: platform/organization/project dashboards made of editable widgets, instead
-- of the 3 hardcoded pages that existed before. scope_id is polymorphic (null for PLATFORM, an
-- organization id for ORGANIZATION, a project id for PROJECT) — no FK, since it targets a
-- different table depending on level; referential integrity for it is enforced in application
-- code (DashboardService), same convention already used for AgentTaskSchedule's own polymorphic
-- target reference elsewhere in this codebase.
CREATE TABLE ares.dashboard (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    level       VARCHAR(20) NOT NULL,
    scope_id    BIGINT,
    name        VARCHAR(100) NOT NULL,
    is_default  BOOLEAN NOT NULL DEFAULT FALSE,
    created_by  BIGINT REFERENCES ares."user"(id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_dashboard_scope ON ares.dashboard (level, scope_id);

-- Exactly one default dashboard per (level, scope_id) — the row every "GET dashboards for this
-- scope" lazily creates on first access and falls back to when a dashboard is deleted.
CREATE UNIQUE INDEX ux_dashboard_default_per_scope ON ares.dashboard (level, scope_id) WHERE is_default;

CREATE TABLE ares.dashboard_widget (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    dashboard_id  BIGINT NOT NULL REFERENCES ares.dashboard(id) ON DELETE CASCADE,
    type          VARCHAR(40) NOT NULL,
    title         VARCHAR(150),
    config        JSONB NOT NULL DEFAULT '{}',
    pos_x         INT NOT NULL DEFAULT 0,
    pos_y         INT NOT NULL DEFAULT 0,
    width         INT NOT NULL,
    height        INT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_dashboard_widget_dashboard ON ares.dashboard_widget (dashboard_id);
