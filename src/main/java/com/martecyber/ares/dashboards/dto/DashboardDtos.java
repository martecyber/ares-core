package com.martecyber.ares.dashboards.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.martecyber.ares.dashboards.Dashboard;
import com.martecyber.ares.dashboards.DashboardLevel;
import com.martecyber.ares.dashboards.DashboardWidget;
import com.martecyber.ares.dashboards.DashboardWidgetType;

import java.util.List;

public final class DashboardDtos {

    private DashboardDtos() {}

    /** One row in the level/scope dropdown (or the template picker) — {@link #list} never needs
     *  the full widget layout. {@code level}/{@code description} are mostly template-oriented
     *  (a real dashboard's caller already knows its own level and never sets a description) but
     *  harmless to always include, so the frontend template picker/list can reuse one shape. */
    public record DashboardSummary(Long id, DashboardLevel level, String name, boolean isDefault, String description) {
        public static DashboardSummary from(Dashboard d) {
            return new DashboardSummary(d.getId(), d.getLevel(), d.getName(), d.isDefault(), d.getDescription());
        }
    }

    /** {@code type} is null when the stored value isn't a current {@link DashboardWidgetType}
     *  constant (see {@link DashboardWidget#getType()}) — {@code typeName} still carries the raw
     *  stored string in that case, so the frontend can render an "unsupported widget: X"
     *  placeholder instead of the widget just silently vanishing from the grid. */
    public record WidgetDto(Long id, DashboardWidgetType type, String typeName, String title, JsonNode config,
                             int posX, int posY, int width, int height) {}

    public record DashboardDto(Long id, DashboardLevel level, Long scopeId, String name, boolean isDefault,
                                boolean isTemplate, String description, boolean presentable, List<WidgetDto> widgets) {}

    /** {@code POST /dashboards} — a brand-new, empty dashboard for a scope. */
    public record CreateDashboardRequest(DashboardLevel level, Long scopeId, String name) {}

    /** {@code PUT /dashboards/{id}} — any field may be null/omitted to leave it unchanged
     *  (empty string for {@code description} clears it). {@code presentable} gates whether this
     *  dashboard can be added to a SOC-screen presentation — see DashboardPresentationService. */
    public record UpdateDashboardRequest(String name, Boolean isDefault, String description, Boolean presentable) {}

    /** {@code POST /dashboards/templates} — a brand-new, empty, reusable template for a level. */
    public record CreateDashboardTemplateRequest(DashboardLevel level, String name, String description) {}

    /** {@code POST /dashboards/from-template/{templateId}} — a real dashboard for {@code scopeId},
     *  seeded from the template's current widgets. {@code name} defaults to the template's own
     *  name when blank/omitted. */
    public record CreateFromTemplateRequest(Long scopeId, String name) {}

    /** {@code POST /dashboards/{id}/save-as-template} — snapshots an existing (real) dashboard's
     *  current widget layout into a brand-new template row; the source dashboard is untouched.
     *  {@code name} defaults to the source dashboard's own name when blank/omitted. */
    public record SaveAsTemplateRequest(String name, String description) {}

    /** One widget as sent by the grid editor on save — {@code id} is null for a newly added
     *  widget, otherwise identifies an existing row to update in place. */
    public record WidgetInput(Long id, DashboardWidgetType type, String title, JsonNode config,
                               int posX, int posY, int width, int height) {}

    public record SaveWidgetsRequest(List<WidgetInput> widgets) {}

    public static WidgetDto toWidgetDto(DashboardWidget w, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        JsonNode config;
        try { config = mapper.readTree(w.getConfig()); }
        catch (Exception e) { config = mapper.createObjectNode(); }
        return new WidgetDto(w.getId(), w.getType(), w.getTypeName(), w.getTitle(), config,
            w.getPosX(), w.getPosY(), w.getWidth(), w.getHeight());
    }
}
