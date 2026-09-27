package com.martecyber.ares.dashboards;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure unit coverage for the per-level widget catalog gate {@link DashboardService#saveWidgets}
 *  enforces server-side (defense in depth behind the frontend catalog) — no Spring context/DB
 *  needed, this is plain enum logic. */
class DashboardWidgetTypeTest {

    @Test
    void genericAqlWidgetsAllowedAtOrgAndProjectOnly() {
        // Not PLATFORM — no entity is both scoped enough to count there and has a real list view
        // to click through to (see DashboardWidgetType's own javadoc for why).
        assertFalse(DashboardWidgetType.AQL_COUNT.allowedAt(DashboardLevel.PLATFORM));
        assertFalse(DashboardWidgetType.AQL_CHART.allowedAt(DashboardLevel.PLATFORM));
        assertTrue(DashboardWidgetType.AQL_COUNT.allowedAt(DashboardLevel.ORGANIZATION));
        assertTrue(DashboardWidgetType.AQL_CHART.allowedAt(DashboardLevel.ORGANIZATION));
        assertTrue(DashboardWidgetType.AQL_COUNT.allowedAt(DashboardLevel.PROJECT));
        assertTrue(DashboardWidgetType.AQL_CHART.allowedAt(DashboardLevel.PROJECT));
    }

    @Test
    void bespokeWidgetsAreLevelExclusive() {
        assertTrue(DashboardWidgetType.ORG_CAROUSEL.allowedAt(DashboardLevel.PLATFORM));
        assertFalse(DashboardWidgetType.ORG_CAROUSEL.allowedAt(DashboardLevel.ORGANIZATION));
        assertFalse(DashboardWidgetType.ORG_CAROUSEL.allowedAt(DashboardLevel.PROJECT));

        assertTrue(DashboardWidgetType.ACTIVE_PROJECTS_LIST.allowedAt(DashboardLevel.ORGANIZATION));
        assertFalse(DashboardWidgetType.ACTIVE_PROJECTS_LIST.allowedAt(DashboardLevel.PLATFORM));
        assertFalse(DashboardWidgetType.ACTIVE_PROJECTS_LIST.allowedAt(DashboardLevel.PROJECT));

        assertTrue(DashboardWidgetType.PROJECT_DATE_PROGRESS.allowedAt(DashboardLevel.PROJECT));
        assertFalse(DashboardWidgetType.PROJECT_DATE_PROGRESS.allowedAt(DashboardLevel.PLATFORM));
        assertFalse(DashboardWidgetType.PROJECT_DATE_PROGRESS.allowedAt(DashboardLevel.ORGANIZATION));
    }

    @Test
    void aqlListAllowedAtOrgAndProjectOnly() {
        assertFalse(DashboardWidgetType.AQL_LIST.allowedAt(DashboardLevel.PLATFORM));
        assertTrue(DashboardWidgetType.AQL_LIST.allowedAt(DashboardLevel.ORGANIZATION));
        assertTrue(DashboardWidgetType.AQL_LIST.allowedAt(DashboardLevel.PROJECT));
    }
}
