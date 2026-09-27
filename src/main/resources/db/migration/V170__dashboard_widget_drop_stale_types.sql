SET search_path TO ares, public;

-- The dashboards remodel replaced FINDING_SLA_DUE/FINDING_SLA_OVERDUE/URGENT_FINDINGS_LIST/
-- RECENT_FINDINGS_TABLE with the generic AQL_COUNT/AQL_LIST widgets (Finding.slaDeadline made
-- "SLA count"/"urgent findings sorted by SLA" plain AQL). Those 4 old type strings were removed
-- from the DashboardWidgetType Java enum outright — any dashboard_widget row still carrying one
-- (seeded before this change, on an environment that had already loaded its dashboards) makes
-- Hibernate throw IllegalArgumentException reading it back (EnumJavaType.fromName has no match),
-- which crashes the *entire* GET/PUT for that dashboard, not just the one stale widget.
--
-- Deleting rather than remapping: each row's AQL/entity config was hand-authored per-seed and
-- there's no generic way to reconstruct an equivalent AQL_COUNT/AQL_LIST config from the old
-- type alone. The operator can re-add the modern equivalent from the widget picker in a few
-- clicks; leaving the dashboard able to load at all again is the priority.
DELETE FROM dashboard_widget
WHERE type NOT IN (
    'AQL_COUNT', 'AQL_CHART', 'AQL_LIST',
    'ORG_CAROUSEL', 'SCHEDULE_CALENDAR', 'CONTINUOUS_PROJECTS',
    'ACTIVE_PROJECTS_LIST',
    'PROJECT_INFO_STRIP', 'PROJECT_DATE_PROGRESS', 'MONITOR_STATS',
    'PROJECT_RULES_LIST', 'PROJECT_SCOPE_SUMMARY', 'PROJECT_TEAM_LIST'
);
