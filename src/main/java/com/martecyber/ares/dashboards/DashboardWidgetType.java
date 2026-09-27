package com.martecyber.ares.dashboards;

import java.util.EnumSet;
import java.util.Set;

/** Every widget type the dashboards system knows how to render/execute. {@code AQL_COUNT}/{@code
 *  AQL_CHART} are generic and available at ORGANIZATION/PROJECT — not PLATFORM, since no entity
 *  they can query is both scoped enough to count there and has a real list view to click through
 *  to (see {@code dashboardEntityListRoute} client-side and {@link DashboardWidgetDataService}'s
 *  own entity dispatch, neither of which supports a platform-wide finding/asset/detection view).
 *  The rest are bespoke, level-specific lift-and-shifts of what the 3 hardcoded dashboard pages
 *  used to render inline. {@link #allowedAt} is enforced server-side (defense in depth — the
 *  frontend catalog in {@code dashboardWidgets.ts} is the actual UX source of truth for what's
 *  offered per level). */
public enum DashboardWidgetType {
    AQL_COUNT(EnumSet.of(DashboardLevel.ORGANIZATION, DashboardLevel.PROJECT)),
    AQL_CHART(EnumSet.of(DashboardLevel.ORGANIZATION, DashboardLevel.PROJECT)),
    // A small AQL-filtered/sorted row list (finding/asset/detection) — replaces what used to be
    // the bespoke URGENT_FINDINGS_LIST/RECENT_FINDINGS_TABLE widgets now that Finding.slaDeadline
    // makes "sort by SLA deadline" (urgent findings' whole reason to exist) plain AQL too.
    AQL_LIST(EnumSet.of(DashboardLevel.ORGANIZATION, DashboardLevel.PROJECT)),

    ORG_CAROUSEL(EnumSet.of(DashboardLevel.PLATFORM)),
    SCHEDULE_CALENDAR(EnumSet.of(DashboardLevel.PLATFORM)),
    CONTINUOUS_PROJECTS(EnumSet.of(DashboardLevel.PLATFORM)),

    ACTIVE_PROJECTS_LIST(EnumSet.of(DashboardLevel.ORGANIZATION)),
    ORG_INFO_STRIP(EnumSet.of(DashboardLevel.ORGANIZATION)),

    PROJECT_IDENTITY_STRIP(EnumSet.of(DashboardLevel.PROJECT)),
    PROJECT_INFO_STRIP(EnumSet.of(DashboardLevel.PROJECT)),
    PROJECT_DATE_PROGRESS(EnumSet.of(DashboardLevel.PROJECT)),
    MONITOR_STATS(EnumSet.of(DashboardLevel.PROJECT)),
    PROJECT_RULES_LIST(EnumSet.of(DashboardLevel.PROJECT)),
    PROJECT_SCOPE_SUMMARY(EnumSet.of(DashboardLevel.PROJECT)),
    PROJECT_TEAM_LIST(EnumSet.of(DashboardLevel.PROJECT));

    private final Set<DashboardLevel> allowedLevels;

    DashboardWidgetType(Set<DashboardLevel> allowedLevels) {
        this.allowedLevels = allowedLevels;
    }

    public boolean allowedAt(DashboardLevel level) {
        return allowedLevels.contains(level);
    }
}
