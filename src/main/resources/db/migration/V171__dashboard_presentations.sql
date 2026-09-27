-- Dashboard presentations: a named, ordered playlist of existing dashboards (of ANY level —
-- platform/organization/project, mixed freely) that auto-rotates every rotation_seconds, meant
-- for a SOC big-screen display. Unlike Dashboard.scope_id's deliberately polymorphic no-FK
-- convention, dashboard_id here is a real FK straight to ares.dashboard(id) — a presentation item
-- always points at one concrete dashboard row regardless of that dashboard's own level, so no
-- level-specific branching is ever needed in this feature, just an id. ON DELETE CASCADE on both
-- FKs means a deleted dashboard quietly drops out of any presentation, and a deleted presentation
-- cleans up its own items.
CREATE TABLE ares.dashboard_presentation (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name              VARCHAR(150) NOT NULL,
    rotation_seconds  INT NOT NULL DEFAULT 30,
    created_by        BIGINT REFERENCES ares."user"(id) ON DELETE SET NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE ares.dashboard_presentation_item (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    presentation_id  BIGINT NOT NULL REFERENCES ares.dashboard_presentation(id) ON DELETE CASCADE,
    dashboard_id     BIGINT NOT NULL REFERENCES ares.dashboard(id) ON DELETE CASCADE,
    sort_order       INT NOT NULL,
    UNIQUE (presentation_id, dashboard_id)
);

CREATE INDEX ix_dashboard_presentation_item_presentation ON ares.dashboard_presentation_item (presentation_id);
