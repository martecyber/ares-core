package com.martecyber.ares.dashboards;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.dashboards.dto.DashboardDtos;
import com.martecyber.ares.dashboards.dto.DashboardDtos.*;
import com.martecyber.ares.dashboards.dto.DashboardPresentationDtos.DashboardBrowseEntry;
import com.martecyber.ares.organizations.OrganizationRepository;
import com.martecyber.ares.projects.ProjectRepository;
import com.martecyber.ares.projects.ProjectService;
import com.martecyber.ares.users.OrgScopeService;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CRUD + permission checks + lazy default-dashboard creation for the dashboards remodel. Every
 * scope (the platform singleton, an organization, a project) gets its "Default" dashboard —
 * pre-populated with the widget set that reproduces what the 3 hardcoded pages used to show —
 * created lazily on first {@link #list}, rather than a migration backfill (works for
 * orgs/projects created after this ships too, with no separate seeding step).
 */
@Service
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    /** Mirrors dashboardWidgets.ts's DASHBOARD_AQL_ENTITIES_BY_LEVEL — which entity an
     *  AQL_COUNT/AQL_CHART/AQL_LIST widget's config.entity may name, per dashboard level. Not
     *  PLATFORM: none of those three widget types are allowed there at all. */
    private static final Map<DashboardLevel, Set<String>> ALLOWED_AQL_ENTITIES = Map.of(
        DashboardLevel.ORGANIZATION, Set.of("finding", "asset"),
        DashboardLevel.PROJECT, Set.of("finding", "asset", "detection")
    );

    private final DashboardRepository repo;
    private final DashboardWidgetRepository widgetRepo;
    private final OrgScopeService orgScope;
    private final ProjectService projectService;
    private final DashboardWidgetDataService widgetDataService;
    private final OrganizationRepository orgRepo;
    private final ProjectRepository projectRepo;
    private final ObjectMapper mapper = new ObjectMapper();

    public DashboardService(DashboardRepository repo, DashboardWidgetRepository widgetRepo,
                             OrgScopeService orgScope, ProjectService projectService,
                             DashboardWidgetDataService widgetDataService,
                             OrganizationRepository orgRepo, ProjectRepository projectRepo) {
        this.repo = repo;
        this.widgetRepo = widgetRepo;
        this.orgScope = orgScope;
        this.projectService = projectService;
        this.widgetDataService = widgetDataService;
        this.orgRepo = orgRepo;
        this.projectRepo = projectRepo;
    }

    // ── Permissions ──────────────────────────────────────────────────────────

    public void assertViewAccess(DashboardLevel level, Long scopeId) {
        var auth = currentAuth();
        if (level == DashboardLevel.PLATFORM) {
            if (!isStaff(auth)) throw new AccessDeniedException("Platform dashboards are staff-only");
            return;
        }
        if (level == DashboardLevel.ORGANIZATION) orgScope.assertOrgAccess(auth, scopeId);
        else orgScope.assertProjectAccess(auth, scopeId);
    }

    public void assertEditAccess(DashboardLevel level, Long scopeId) {
        var auth = currentAuth();
        if (level == DashboardLevel.PLATFORM) {
            if (!isStaff(auth)) throw new AccessDeniedException("Platform dashboards are staff-only");
            return;
        }
        boolean hasEditRole = isStaff(auth) || hasAuthority(auth, "ROLE_CLIENT_ADMIN");
        if (!hasEditRole) throw new AccessDeniedException("No dashboard edit access");
        if (level == DashboardLevel.ORGANIZATION) orgScope.assertOrgAccess(auth, scopeId);
        else orgScope.assertProjectAccess(auth, scopeId);
    }

    /** Template-aware overloads for the generic by-id endpoints (get/update/delete/saveWidgets/
     *  getWidgetData), which are reused as-is to edit a template's widgets. A template's scopeId
     *  is always null regardless of level, so the scope-membership checks above (assertOrgAccess/
     *  assertProjectAccess with a null id) don't apply — staff-only, same bar as /dashboards/browse. */
    private void assertViewAccess(Dashboard d) {
        if (d.isTemplate()) {
            if (!isStaff(currentAuth())) throw new AccessDeniedException("Dashboard templates are staff-only");
            return;
        }
        assertViewAccess(d.getLevel(), d.getScopeId());
    }

    private void assertEditAccess(Dashboard d) {
        if (d.isTemplate()) {
            if (!isStaff(currentAuth())) throw new AccessDeniedException("Dashboard templates are staff-only");
            return;
        }
        assertEditAccess(d.getLevel(), d.getScopeId());
    }

    private boolean isStaff(Authentication auth) {
        return orgScope.isPlatformAdmin(auth) || hasAuthority(auth, "ROLE_MSSP_OPERATOR");
    }

    private boolean hasAuthority(Authentication auth, String role) {
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(role::equals);
    }

    private static Authentication currentAuth() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    // ── Reads ────────────────────────────────────────────────────────────────

    @Transactional
    public List<DashboardSummary> list(DashboardLevel level, Long scopeId) {
        assertViewAccess(level, scopeId);
        List<Dashboard> existing = repo.findByLevelAndScopeIdAndIsTemplateFalseOrderByIsDefaultDescNameAsc(level, scopeId);
        if (existing.isEmpty()) {
            existing = List.of(createDefault(level, scopeId));
        }
        return existing.stream().map(DashboardSummary::from).toList();
    }

    @Transactional
    public DashboardDto get(Long id) {
        Dashboard d = repo.findById(id).orElseThrow(() -> NotFoundException.of("dashboard", id));
        assertViewAccess(d);
        List<WidgetDto> widgets = widgetRepo.findByDashboardId(d.getId()).stream()
            .map(w -> DashboardDtos.toWidgetDto(w, mapper))
            .toList();
        return new DashboardDto(d.getId(), d.getLevel(), d.getScopeId(), d.getName(), d.isDefault(),
            d.isTemplate(), d.getDescription(), d.isPresentable(), widgets);
    }

    /** Every PRESENTABLE, non-template dashboard platform-wide (staff-only, capped), with a
     *  resolved human-readable {@code scopeLabel} — the source for the "add a dashboard to this
     *  presentation" picker, which needs to span every level (platform/organization/project) in
     *  one browsable, readable list. Only surfacing presentable dashboards here means the picker
     *  itself can never offer one a dashboard's own owner hasn't opted in — {@link
     *  DashboardPresentationService#saveItems} re-checks the same flag server-side as a second
     *  line of defense against a stale/hand-crafted request. {@code q} substring-matches
     *  (case-insensitive) against the dashboard's own name or its resolved scope label, so
     *  searching an org/project name works same as searching a dashboard's own name. */
    @Transactional
    public List<DashboardBrowseEntry> browseDashboards(String q) {
        List<Dashboard> all = repo.findAll(org.springframework.data.domain.PageRequest.of(0, 500)).getContent().stream()
            .filter(d -> d.isPresentable() && !d.isTemplate())
            .toList();
        Map<Long, DashboardScopeLabels.ScopeInfo> scopeInfo = DashboardScopeLabels.resolve(all, orgRepo, projectRepo);
        String needle = q == null ? "" : q.trim().toLowerCase(java.util.Locale.ROOT);
        return all.stream()
            .map(d -> new DashboardBrowseEntry(d.getId(), d.getName(), d.getLevel(), d.getScopeId(),
                scopeInfo.get(d.getId()).label(), d.isDefault()))
            .filter(e -> needle.isEmpty()
                || e.name().toLowerCase(java.util.Locale.ROOT).contains(needle)
                || (e.scopeLabel() != null && e.scopeLabel().toLowerCase(java.util.Locale.ROOT).contains(needle)))
            .sorted(java.util.Comparator.comparing(DashboardBrowseEntry::scopeLabel, java.util.Comparator.nullsLast(String::compareTo))
                .thenComparing(DashboardBrowseEntry::name))
            .toList();
    }

    /** Executes a single widget's data query — split out from the old whole-dashboard bulk
     *  endpoint (see git history) so one slow/heavy widget can no longer make the entire
     *  dashboard's load time out: the frontend now fires one of these per widget, in parallel,
     *  each with its own independent success/loading/error state (DashboardHost.vue). Deliberately
     *  NOT {@code @Transactional}: {@code widgetDataService.dataFor} delegates to an
     *  already-{@code @Transactional} entity-service method (Finding/Asset/DetectionService's
     *  countByAql/listByAql/countGroupedByAql) — wrapping this method too would just have it join
     *  that same transaction for no benefit, since there's only ever one widget's work here now. */
    public Object getWidgetData(Long dashboardId, Long widgetId) {
        Dashboard d = repo.findById(dashboardId).orElseThrow(() -> NotFoundException.of("dashboard", dashboardId));
        assertViewAccess(d);
        // A template has no real scope to pull live AQL data from — same "no data" contract as a
        // bespoke widget type with nothing server-computed (frontend already renders that as empty).
        if (d.isTemplate()) return null;
        DashboardWidget w = widgetRepo.findById(widgetId)
            .filter(x -> dashboardId.equals(x.getDashboardId()))
            .orElseThrow(() -> NotFoundException.of("dashboard widget", widgetId));

        com.fasterxml.jackson.databind.JsonNode config;
        try { config = mapper.readTree(w.getConfig()); }
        catch (Exception e) { config = mapper.createObjectNode(); }

        try {
            return widgetDataService.dataFor(w, config, d.getLevel(), d.getScopeId());
        } catch (Exception e) {
            log.warn("Dashboard {} widget {} ({}) failed to load", d.getId(), w.getId(), w.getTypeName(), e);
            throw e;
        }
    }

    // ── Writes ───────────────────────────────────────────────────────────────

    @Transactional
    public DashboardSummary create(CreateDashboardRequest req) {
        assertEditAccess(req.level(), req.scopeId());
        Dashboard d = new Dashboard();
        d.setLevel(req.level());
        d.setScopeId(req.scopeId());
        d.setName(req.name() == null || req.name().isBlank() ? "New dashboard" : req.name().trim());
        d.setDefault(repo.countByLevelAndScopeIdAndIsTemplateFalse(req.level(), req.scopeId()) == 0);
        Long userId = currentUserId();
        d.setCreatedBy(userId);
        d = repo.save(d);
        return DashboardSummary.from(d);
    }

    @Transactional
    public DashboardSummary update(Long id, UpdateDashboardRequest req) {
        Dashboard d = repo.findById(id).orElseThrow(() -> NotFoundException.of("dashboard", id));
        assertEditAccess(d);
        if (req.name() != null && !req.name().isBlank()) d.setName(req.name().trim());
        if (req.description() != null) d.setDescription(req.description().isBlank() ? null : req.description().trim());
        if (req.presentable() != null) d.setPresentable(req.presentable());
        if (Boolean.TRUE.equals(req.isDefault()) && !d.isDefault()) {
            // ux_dashboard_default_per_scope is a non-deferred partial unique index, checked
            // immediately per-statement — clearing the previous default must actually hit the DB
            // (saveAndFlush, not save) before this dashboard's own is_default flips to true.
            // Plain save() only marks it dirty; Hibernate's flush order for two already-managed
            // entities follows persistence-context entry order, not code order, and `d` (loaded
            // first, at the top of this method) can flush before `prev` (loaded second here) —
            // leaving two rows with is_default=true at once and failing the UPDATE with a unique
            // violation (reported as PUT /dashboards/{id} 500 when switching an org's default).
            repo.findByLevelAndScopeIdAndIsTemplateFalseAndIsDefaultTrue(d.getLevel(), d.getScopeId())
                .ifPresent(prev -> { prev.setDefault(false); repo.saveAndFlush(prev); });
            d.setDefault(true);
        }
        d.setUpdatedAt(java.time.OffsetDateTime.now());
        d = repo.save(d);
        return DashboardSummary.from(d);
    }

    @Transactional
    public void delete(Long id) {
        Dashboard d = repo.findById(id).orElseThrow(() -> NotFoundException.of("dashboard", id));
        assertEditAccess(d);
        // Templates aren't subject to "a scope always needs >=1 dashboard" — they have no scope.
        if (!d.isTemplate() && repo.countByLevelAndScopeIdAndIsTemplateFalse(d.getLevel(), d.getScopeId()) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Can't delete the only dashboard for this scope");
        }
        boolean wasDefault = d.isDefault();
        repo.delete(d);
        if (wasDefault) {
            repo.findByLevelAndScopeIdAndIsTemplateFalseOrderByIsDefaultDescNameAsc(d.getLevel(), d.getScopeId()).stream()
                .findFirst()
                .ifPresent(next -> { next.setDefault(true); repo.save(next); });
        }
    }

    @Transactional
    public void saveWidgets(Long dashboardId, SaveWidgetsRequest req) {
        Dashboard d = repo.findById(dashboardId).orElseThrow(() -> NotFoundException.of("dashboard", dashboardId));
        assertEditAccess(d);

        for (WidgetInput in : req.widgets()) {
            if (!in.type().allowedAt(d.getLevel())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Widget type " + in.type() + " isn't available at " + d.getLevel() + " level");
            }
            if (in.type() == DashboardWidgetType.AQL_COUNT || in.type() == DashboardWidgetType.AQL_CHART
                    || in.type() == DashboardWidgetType.AQL_LIST) {
                String entity = in.config() != null && in.config().hasNonNull("entity") ? in.config().get("entity").asText() : null;
                Set<String> allowed = ALLOWED_AQL_ENTITIES.getOrDefault(d.getLevel(), Set.of());
                if (entity == null || !allowed.contains(entity)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Entity '" + entity + "' isn't queryable at " + d.getLevel() + " level — pick one of " + allowed);
                }
            }
        }

        List<Long> keepIds = req.widgets().stream().map(WidgetInput::id).filter(java.util.Objects::nonNull).toList();
        for (DashboardWidget existing : widgetRepo.findByDashboardId(dashboardId)) {
            if (!keepIds.contains(existing.getId())) widgetRepo.delete(existing);
        }

        for (WidgetInput in : req.widgets()) {
            DashboardWidget w = in.id() != null
                ? widgetRepo.findById(in.id()).orElseThrow(() -> NotFoundException.of("dashboard widget", in.id()))
                : new DashboardWidget();
            w.setDashboardId(dashboardId);
            w.setType(in.type());
            w.setTitle(in.title());
            w.setConfig(in.config() == null ? "{}" : in.config().toString());
            w.setPosX(in.posX());
            w.setPosY(in.posY());
            w.setWidth(in.width());
            w.setHeight(in.height());
            w.setUpdatedAt(java.time.OffsetDateTime.now());
            widgetRepo.save(w);
        }
    }

    // ── Templates ────────────────────────────────────────────────────────────
    // A template IS a Dashboard row (isTemplate=true, scopeId always null) — see Dashboard's own
    // doc comment. Editing a template's widgets reuses get()/saveWidgets() above unchanged; only
    // template list/create and "instantiate a real dashboard from one" are new.

    @Transactional
    public List<DashboardSummary> listTemplates(DashboardLevel level) {
        if (!isStaff(currentAuth())) throw new AccessDeniedException("Dashboard templates are staff-only");
        return repo.findByLevelAndIsTemplateTrueOrderByNameAsc(level).stream().map(DashboardSummary::from).toList();
    }

    @Transactional
    public DashboardSummary createTemplate(DashboardLevel level, String name, String description) {
        if (!isStaff(currentAuth())) throw new AccessDeniedException("Dashboard templates are staff-only");
        Dashboard d = new Dashboard();
        d.setLevel(level);
        d.setScopeId(null);
        d.setTemplate(true);
        d.setName(name == null || name.isBlank() ? "New template" : name.trim());
        d.setDescription(description == null || description.isBlank() ? null : description.trim());
        d.setCreatedBy(currentUserId());
        d = repo.save(d);
        return DashboardSummary.from(d);
    }

    /** Creates a real dashboard for {@code scopeId}, seeded from {@code templateId}'s current
     *  widgets — same create-then-bulk-insert-widgets shape as {@link #createDefault}, just
     *  reading the widget list from a persisted template instead of the hardcoded seed switch.
     *  Widget types re-validated against the target level defensively (skipped + logged, not
     *  fatal) in case a type was retired since the template was authored. */
    @Transactional
    public DashboardSummary createFromTemplate(Long templateId, Long scopeId, String name) {
        Dashboard tpl = repo.findById(templateId).orElseThrow(() -> NotFoundException.of("dashboard template", templateId));
        if (!tpl.isTemplate()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dashboard " + templateId + " is not a template");
        }
        assertEditAccess(tpl.getLevel(), scopeId); // real target scope — normal scope-based check

        Dashboard d = new Dashboard();
        d.setLevel(tpl.getLevel());
        d.setScopeId(scopeId);
        d.setName(name == null || name.isBlank() ? tpl.getName() : name.trim());
        d.setDefault(repo.countByLevelAndScopeIdAndIsTemplateFalse(tpl.getLevel(), scopeId) == 0);
        d.setCreatedBy(currentUserId());
        d = repo.save(d);

        for (DashboardWidget src : widgetRepo.findByDashboardId(tpl.getId())) {
            DashboardWidgetType type = src.getType();
            if (type == null || !type.allowedAt(d.getLevel())) {
                log.warn("Skipping widget {} ({}) copying template {} to dashboard {} — no longer valid at {} level",
                    src.getId(), src.getTypeName(), templateId, d.getId(), d.getLevel());
                continue;
            }
            DashboardWidget w = new DashboardWidget();
            w.setDashboardId(d.getId());
            w.setType(type);
            w.setTitle(src.getTitle());
            w.setConfig(src.getConfig());
            w.setPosX(src.getPosX());
            w.setPosY(src.getPosY());
            w.setWidth(src.getWidth());
            w.setHeight(src.getHeight());
            widgetRepo.save(w);
        }
        return DashboardSummary.from(d);
    }

    /** Snapshots {@code dashboardId}'s current widgets into a brand-new template — the reverse
     *  direction of {@link #createFromTemplate}. The source dashboard is only ever read; nothing
     *  about it (including its own {@code isDefault}) changes. Staff-only like every other
     *  template write, plus the normal view check on the source so a staff caller still can't
     *  snapshot a dashboard they have no access to. */
    @Transactional
    public DashboardSummary createTemplateFromDashboard(Long dashboardId, String name, String description) {
        return createTemplateFromDashboard(dashboardId, name, description, false);
    }

    /** @param overwrite when {@code false} and a template at this level already has this exact
     *      name, throws {@link com.martecyber.ares.common.DuplicateNameException} instead of
     *      creating a second one — same call {@link com.martecyber.ares.findings.templates.FindingTemplateService#createFromFinding}
     *      makes. When {@code true}, that existing template's widgets are replaced in place
     *      (same row id) rather than creating a new one. */
    @Transactional
    public DashboardSummary createTemplateFromDashboard(Long dashboardId, String name, String description, boolean overwrite) {
        if (!isStaff(currentAuth())) throw new AccessDeniedException("Dashboard templates are staff-only");
        Dashboard src = repo.findById(dashboardId).orElseThrow(() -> NotFoundException.of("dashboard", dashboardId));
        assertViewAccess(src);

        String resolvedName = name == null || name.isBlank() ? src.getName() : name.trim();
        Dashboard d;
        var existing = repo.findByLevelAndIsTemplateTrueAndNameIgnoreCase(src.getLevel(), resolvedName);
        if (existing.isPresent()) {
            if (!overwrite) {
                throw new com.martecyber.ares.common.DuplicateNameException(
                    "A " + src.getLevel().name().toLowerCase() + "-level dashboard template named \"" + resolvedName + "\" already exists");
            }
            d = existing.get();
            widgetRepo.deleteByDashboardId(d.getId());
        } else {
            d = new Dashboard();
            d.setLevel(src.getLevel());
            d.setScopeId(null);
            d.setTemplate(true);
            d.setCreatedBy(currentUserId());
        }
        d.setName(resolvedName);
        d.setDescription(description == null || description.isBlank() ? null : description.trim());
        d = repo.save(d);

        for (DashboardWidget src2 : widgetRepo.findByDashboardId(src.getId())) {
            if (src2.getType() == null) continue; // unrecognized type — nothing valid to copy
            DashboardWidget w = new DashboardWidget();
            w.setDashboardId(d.getId());
            w.setType(src2.getType());
            w.setTitle(src2.getTitle());
            w.setConfig(src2.getConfig());
            w.setPosX(src2.getPosX());
            w.setPosY(src2.getPosY());
            w.setWidth(src2.getWidth());
            w.setHeight(src2.getHeight());
            widgetRepo.save(w);
        }
        return DashboardSummary.from(d);
    }

    // ── Default-dashboard seeding ────────────────────────────────────────────

    private Dashboard createDefault(DashboardLevel level, Long scopeId) {
        Dashboard d = new Dashboard();
        d.setLevel(level);
        d.setScopeId(scopeId);
        d.setName("Default");
        d.setDefault(true);
        d.setCreatedBy(currentUserId());
        d = repo.save(d);

        for (WidgetSeed seed : defaultWidgetSeeds(level, scopeId)) {
            DashboardWidget w = new DashboardWidget();
            w.setDashboardId(d.getId());
            w.setType(seed.type());
            w.setTitle(seed.title());
            w.setConfig(seed.config());
            w.setPosX(seed.x());
            w.setPosY(seed.y());
            w.setWidth(seed.w());
            w.setHeight(seed.h());
            widgetRepo.save(w);
        }
        return d;
    }

    private record WidgetSeed(DashboardWidgetType type, int x, int y, int w, int h, String config, String title) {
        WidgetSeed(DashboardWidgetType type, int x, int y, int w, int h) { this(type, x, y, w, h, "{}", null); }
    }

    private List<WidgetSeed> defaultWidgetSeeds(DashboardLevel level, Long scopeId) {
        List<WidgetSeed> seeds = new ArrayList<>();
        switch (level) {
            case PLATFORM -> {
                seeds.add(new WidgetSeed(DashboardWidgetType.ORG_CAROUSEL, 0, 0, 12, 9));
                seeds.add(new WidgetSeed(DashboardWidgetType.SCHEDULE_CALENDAR, 0, 9, 8, 12));
                seeds.add(new WidgetSeed(DashboardWidgetType.CONTINUOUS_PROJECTS, 8, 9, 4, 12));
            }
            case ORGANIZATION -> {
                seeds.add(new WidgetSeed(DashboardWidgetType.ORG_INFO_STRIP, 0, 0, 12, 2));
                seeds.add(aqlCount(DashboardWidgetType.AQL_COUNT, "Critical findings", 0, 2, "finding",
                    "priority == \"P0\" AND isDraft == false AND isOpen == true"));
                seeds.add(aqlCount(DashboardWidgetType.AQL_COUNT, "High findings", 2, 2, "finding",
                    "priority == \"P1\" AND isDraft == false AND isOpen == true"));
                seeds.add(aqlCount(DashboardWidgetType.AQL_COUNT, "Medium findings", 4, 2, "finding",
                    "priority == \"P2\" AND isDraft == false AND isOpen == true"));
                seeds.add(aqlCount(DashboardWidgetType.AQL_COUNT, "Low findings", 6, 2, "finding",
                    "priority == \"P3\" AND isDraft == false AND isOpen == true"));
                seeds.add(aqlCount(DashboardWidgetType.AQL_COUNT, "Due in 7 days", 8, 2, "finding",
                    "slaDeadline != null AND slaDeadline >= now AND slaDeadline <= now+7d AND isDraft == false AND isOpen == true"));
                seeds.add(aqlCount(DashboardWidgetType.AQL_COUNT, "SLA exceeded", 10, 2, "finding",
                    "slaDeadline != null AND slaDeadline < now AND isDraft == false AND isOpen == true"));
                seeds.add(new WidgetSeed(DashboardWidgetType.ACTIVE_PROJECTS_LIST, 0, 4, 6, 5));
                seeds.add(aqlList(6, 4, 6, 5, "Urgent findings", "finding",
                    "slaDeadline != null AND isDraft == false AND isOpen == true", "slaDeadline", "asc", 10));
            }
            case PROJECT -> {
                seeds.add(new WidgetSeed(DashboardWidgetType.PROJECT_IDENTITY_STRIP, 0, 0, 12, 2));
                seeds.add(new WidgetSeed(DashboardWidgetType.PROJECT_INFO_STRIP, 0, 2, 12, 2));
                seeds.add(new WidgetSeed(DashboardWidgetType.PROJECT_DATE_PROGRESS, 0, 4, 12, 2));
                seeds.add(aqlCount(DashboardWidgetType.AQL_COUNT, "Findings", 0, 6, "finding", ""));
                seeds.add(aqlCount(DashboardWidgetType.AQL_COUNT, "Assets", 3, 6, "asset", ""));
                seeds.add(aqlCount(DashboardWidgetType.AQL_COUNT, "Detections", 6, 6, "detection", ""));
                seeds.add(aqlCount(DashboardWidgetType.AQL_COUNT, "New detections", 9, 6, "detection", "status == \"new\""));
                if (projectService.isMonitorProject(scopeId)) {
                    seeds.add(new WidgetSeed(DashboardWidgetType.MONITOR_STATS, 0, 8, 2, 2));
                }
                int y = 10;
                seeds.add(new WidgetSeed(DashboardWidgetType.PROJECT_RULES_LIST, 0, y, 4, 4));
                seeds.add(new WidgetSeed(DashboardWidgetType.PROJECT_SCOPE_SUMMARY, 4, y, 4, 4));
                seeds.add(new WidgetSeed(DashboardWidgetType.PROJECT_TEAM_LIST, 8, y, 4, 4));
                seeds.add(aqlList(0, y + 4, 12, 6, "Recent findings", "finding", "isDraft == false", "createdAt", "desc", 8));
            }
        }
        return seeds;
    }

    private WidgetSeed aqlCount(DashboardWidgetType type, String title, int x, int y, String entity, String aql) {
        var node = mapper.createObjectNode();
        node.put("entity", entity);
        node.put("aql", aql);
        return new WidgetSeed(type, x, y, 2, 2, node.toString(), title);
    }

    private WidgetSeed aqlList(int x, int y, int w, int h, String title, String entity, String aql,
                                String sortField, String sortDir, int limit) {
        var node = mapper.createObjectNode();
        node.put("entity", entity);
        node.put("aql", aql);
        node.put("sortField", sortField);
        node.put("sortDir", sortDir);
        node.put("limit", limit);
        return new WidgetSeed(DashboardWidgetType.AQL_LIST, x, y, w, h, node.toString(), title);
    }

    private Long currentUserId() {
        try { return Long.parseLong(currentAuth().getName()); }
        catch (Exception e) { return null; }
    }
}
