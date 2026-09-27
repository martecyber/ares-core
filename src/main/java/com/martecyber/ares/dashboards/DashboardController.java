package com.martecyber.ares.dashboards;

import com.martecyber.ares.dashboards.dto.DashboardDtos.*;
import com.martecyber.ares.dashboards.dto.DashboardPresentationDtos.DashboardBrowseEntry;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** REST surface for the dashboards remodel — 3-level (platform/organization/project) editable
 *  widget dashboards. Role gate here is deliberately permissive (every authenticated role reaches
 *  every method); the real per-scope view/edit distinction is enforced inside {@link
 *  DashboardService} (staff-only for PLATFORM, org/project-membership + CLIENT_ADMIN-or-staff for
 *  edits otherwise) since it depends on which scope a given dashboard belongs to, not just the
 *  caller's role. */
@RestController
@RequestMapping("/api/v1/dashboards")
@PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
public class DashboardController {

    private final DashboardService service;

    public DashboardController(DashboardService service) {
        this.service = service;
    }

    @GetMapping
    public List<DashboardSummary> list(@RequestParam DashboardLevel level, @RequestParam(required = false) Long scopeId) {
        return service.list(level, scopeId);
    }

    @GetMapping("/{id}")
    public DashboardDto get(@PathVariable Long id) {
        return service.get(id);
    }

    /** Source for the "add a dashboard to this presentation" picker — spans every level, so
     *  staff-only (there's no client-facing use for browsing dashboards platform-wide). */
    @GetMapping("/browse")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<DashboardBrowseEntry> browse(@RequestParam(required = false) String q) {
        return service.browseDashboards(q);
    }

    /** Split from the old whole-dashboard bulk endpoint — see {@link DashboardService#getWidgetData}
     *  — so the frontend can fetch each widget's data independently (one slow widget no longer
     *  blocks/times out the rest of the dashboard). Returns 204 when the widget's type has no
     *  server-computed data (a "bespoke" widget that fetches its own data client-side). */
    @GetMapping("/{id}/widgets/{widgetId}/data")
    public ResponseEntity<Object> widgetData(@PathVariable Long id, @PathVariable Long widgetId) {
        Object data = service.getWidgetData(id, widgetId);
        return data != null ? ResponseEntity.ok(data) : ResponseEntity.noContent().build();
    }

    @PostMapping
    public DashboardSummary create(@RequestBody CreateDashboardRequest req) {
        return service.create(req);
    }

    /** KB-managed, reusable widget layouts, browsable/creatable by level — staff-only, same bar
     *  as {@link #browse}. Editing a template's widgets goes through the generic {@code
     *  GET/PUT /{id}}, {@code PUT /{id}/widgets} below (a template is a Dashboard row). */
    @GetMapping("/templates")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<DashboardSummary> listTemplates(@RequestParam DashboardLevel level) {
        return service.listTemplates(level);
    }

    @PostMapping("/templates")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public DashboardSummary createTemplate(@RequestBody CreateDashboardTemplateRequest req) {
        return service.createTemplate(req.level(), req.name(), req.description());
    }

    /** Instantiates a real dashboard at {@code req.scopeId()} from a template's current widgets —
     *  same edit-access bar as {@link #create} for that real scope (not staff-only: any operator/
     *  client-admin who could create a blank dashboard there can start one from a template too). */
    @PostMapping("/from-template/{templateId}")
    public DashboardSummary createFromTemplate(@PathVariable Long templateId, @RequestBody CreateFromTemplateRequest req) {
        return service.createFromTemplate(templateId, req.scopeId(), req.name());
    }

    /** Snapshots dashboard {@code id}'s current widgets into a brand-new template — the reverse of
     *  {@link #createFromTemplate}. Staff-only, same bar as every other template write; the source
     *  dashboard itself is untouched. */
    @PostMapping("/{id}/save-as-template")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public DashboardSummary saveAsTemplate(@PathVariable Long id,
                                           @RequestParam(defaultValue = "false") boolean overwrite,
                                           @RequestBody SaveAsTemplateRequest req) {
        return service.createTemplateFromDashboard(id, req.name(), req.description(), overwrite);
    }

    @PutMapping("/{id}")
    public DashboardSummary update(@PathVariable Long id, @RequestBody UpdateDashboardRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/widgets")
    public ResponseEntity<Void> saveWidgets(@PathVariable Long id, @RequestBody SaveWidgetsRequest req) {
        service.saveWidgets(id, req);
        return ResponseEntity.noContent().build();
    }
}
