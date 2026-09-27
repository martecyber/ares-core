package com.martecyber.ares.dashboards.dto;

import com.martecyber.ares.dashboards.DashboardLevel;
import com.martecyber.ares.dashboards.DashboardPresentation;

import java.util.List;

public final class DashboardPresentationDtos {

    private DashboardPresentationDtos() {}

    /** One row in the presentations list — never needs the full ordered item list. */
    public record PresentationSummary(Long id, String name, int rotationSeconds, int dashboardCount) {
        public static PresentationSummary from(DashboardPresentation p, int dashboardCount) {
            return new PresentationSummary(p.getId(), p.getName(), p.getRotationSeconds(), dashboardCount);
        }
    }

    /** One dashboard slot in a presentation's rotation, in play order. {@code scopeLabel} is
     *  resolved server-side ("Platform" / the org name / "<org> / <project>") so the frontend
     *  never has to separately look up org/project names to render a readable playlist —
     *  important since a presentation freely mixes dashboards across every level.
     *  {@code organizationId} is the dashboard's effective organization (null for PLATFORM,
     *  {@code scopeId} itself for ORGANIZATION, the project's own org for PROJECT — {@code
     *  scopeId} alone doesn't carry this for PROJECT-level items) — the player needs it to embed
     *  a PROJECT-level dashboard's {@code DashboardHost} with the {@code orgId} its widgets'
     *  click-through routes require. */
    public record PresentationItemDto(Long dashboardId, String dashboardName, DashboardLevel level,
                                       Long scopeId, String scopeLabel, Long organizationId) {}

    public record PresentationDto(Long id, String name, int rotationSeconds, List<PresentationItemDto> items) {}

    /** {@code POST /dashboard-presentations} — {@code rotationSeconds} null defaults to 30. */
    public record CreatePresentationRequest(String name, Integer rotationSeconds) {}

    /** {@code PUT /dashboard-presentations/{id}} — either field may be null/omitted to leave it
     *  unchanged. */
    public record UpdatePresentationRequest(String name, Integer rotationSeconds) {}

    /** {@code PUT /dashboard-presentations/{id}/items} — bulk replace, in play order (mirrors
     *  Dashboard's own SaveWidgetsRequest bulk-replace-on-save convention). */
    public record SavePresentationItemsRequest(List<Long> dashboardIds) {}

    /** One row in the "add a dashboard to this presentation" picker — {@code GET
     *  /dashboards/browse}, spans every level so a presentation can genuinely mix
     *  platform/organization/project dashboards. */
    public record DashboardBrowseEntry(Long id, String name, DashboardLevel level, Long scopeId,
                                        String scopeLabel, boolean isDefault) {}
}
