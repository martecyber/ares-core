-- Reusable dashboard templates (KB-managed, per-level) — a template is literally a `dashboard`
-- row with is_template = true and scope_id always NULL (regardless of level), so the entire
-- widget-editing UI (DashboardHost.vue's dashboardId-pinned mode) and the widget-save/validation
-- endpoints are reused as-is. See DashboardService for the query/access-check adjustments this
-- requires (scope_id already means "no scope" for PLATFORM dashboards today, so every existing
-- (level, scope_id) lookup must now also filter is_template = false to avoid mixing a template
-- into the real dashboard list/counts).
ALTER TABLE ares.dashboard ADD COLUMN is_template BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE ares.dashboard ADD COLUMN description TEXT;
