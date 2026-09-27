package com.martecyber.ares.dashboards;

import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.dashboards.dto.DashboardPresentationDtos.*;
import com.martecyber.ares.organizations.OrganizationRepository;
import com.martecyber.ares.projects.ProjectRepository;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * CRUD for SOC-screen dashboard presentations — a named, ordered playlist of existing dashboards
 * spanning any level (platform/organization/project, freely mixed), auto-rotated by the frontend
 * player. Entirely staff-only (gated at {@link DashboardPresentationController}'s class level —
 * there's no client-facing use case for this feature at all), unlike {@link DashboardService}'s
 * per-scope view/edit distinction, so there's no permission-check surface here beyond that.
 */
@Service
public class DashboardPresentationService {

    private static final int MIN_ROTATION_SECONDS = 5;
    private static final int MAX_ROTATION_SECONDS = 3600;
    private static final int DEFAULT_ROTATION_SECONDS = 30;

    private final DashboardPresentationRepository repo;
    private final DashboardPresentationItemRepository itemRepo;
    private final DashboardRepository dashboardRepo;
    private final OrganizationRepository orgRepo;
    private final ProjectRepository projectRepo;

    public DashboardPresentationService(DashboardPresentationRepository repo, DashboardPresentationItemRepository itemRepo,
                                         DashboardRepository dashboardRepo, OrganizationRepository orgRepo,
                                         ProjectRepository projectRepo) {
        this.repo = repo;
        this.itemRepo = itemRepo;
        this.dashboardRepo = dashboardRepo;
        this.orgRepo = orgRepo;
        this.projectRepo = projectRepo;
    }

    @Transactional
    public List<PresentationSummary> list() {
        return repo.findAllByOrderByNameAsc().stream()
            .map(p -> PresentationSummary.from(p, (int) itemRepo.countByPresentationId(p.getId())))
            .toList();
    }

    @Transactional
    public PresentationDto get(Long id) {
        DashboardPresentation p = repo.findById(id).orElseThrow(() -> NotFoundException.of("dashboard presentation", id));
        List<DashboardPresentationItem> items = itemRepo.findByPresentationIdOrderBySortOrderAsc(id);
        List<Dashboard> dashboards = dashboardRepo.findAllById(items.stream().map(DashboardPresentationItem::getDashboardId).toList());
        Map<Long, Dashboard> dashboardsById = dashboards.stream()
            .collect(java.util.stream.Collectors.toMap(Dashboard::getId, d -> d));
        Map<Long, DashboardScopeLabels.ScopeInfo> scopeInfo = DashboardScopeLabels.resolve(dashboards, orgRepo, projectRepo);

        // A dashboard referenced by an item may have been deleted out from under it — ON DELETE
        // CASCADE means the item itself would already be gone in that case, but a race between
        // this read and a concurrent delete is still possible; skip silently rather than 500.
        // Same treatment for a dashboard whose owner later un-flagged it as presentable: honoring
        // that immediately (rather than only at the next saveItems() edit) is what actually makes
        // the opt-in flag a live gate instead of a one-time check at add-time.
        List<PresentationItemDto> itemDtos = items.stream()
            .map(i -> dashboardsById.get(i.getDashboardId()))
            .filter(java.util.Objects::nonNull)
            .filter(d -> d.isPresentable() && !d.isTemplate())
            .map(d -> {
                var info = scopeInfo.get(d.getId());
                return new PresentationItemDto(d.getId(), d.getName(), d.getLevel(), d.getScopeId(),
                    info.label(), info.organizationId());
            })
            .toList();
        return new PresentationDto(p.getId(), p.getName(), p.getRotationSeconds(), itemDtos);
    }

    @Transactional
    public PresentationSummary create(CreatePresentationRequest req) {
        String name = req.name() == null || req.name().isBlank() ? "New presentation" : req.name().trim();
        DashboardPresentation p = new DashboardPresentation();
        p.setName(name);
        p.setRotationSeconds(clampRotation(req.rotationSeconds()));
        p.setCreatedBy(currentUserId());
        p = repo.save(p);
        return PresentationSummary.from(p, 0);
    }

    @Transactional
    public PresentationSummary update(Long id, UpdatePresentationRequest req) {
        DashboardPresentation p = repo.findById(id).orElseThrow(() -> NotFoundException.of("dashboard presentation", id));
        if (req.name() != null && !req.name().isBlank()) p.setName(req.name().trim());
        if (req.rotationSeconds() != null) p.setRotationSeconds(clampRotation(req.rotationSeconds()));
        p.setUpdatedAt(OffsetDateTime.now());
        p = repo.save(p);
        return PresentationSummary.from(p, (int) itemRepo.countByPresentationId(id));
    }

    @Transactional
    public void delete(Long id) {
        DashboardPresentation p = repo.findById(id).orElseThrow(() -> NotFoundException.of("dashboard presentation", id));
        // Flushed immediately (not deferred to transaction commit) so the DB-level ON DELETE
        // CASCADE on dashboard_presentation_item has actually fired by the time this method
        // returns, same rationale as DashboardService.update()'s saveAndFlush for its own
        // partial-unique-index ordering issue — a caller reading item counts right after this in
        // the same transaction must see the post-cascade state, not a stale pre-flush one.
        repo.delete(p);
        repo.flush();
    }

    @Transactional
    public void saveItems(Long id, SavePresentationItemsRequest req) {
        repo.findById(id).orElseThrow(() -> NotFoundException.of("dashboard presentation", id));
        List<Long> ids = req.dashboardIds();
        List<Dashboard> found = dashboardRepo.findAllById(ids);
        if (found.size() != ids.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "One or more dashboards no longer exist");
        }
        // Second line of defense behind browseDashboards() already only ever offering presentable,
        // non-template dashboards to pick from — a presentation can never end up rotating through
        // a dashboard its own owner hasn't explicitly opted in, even via a stale/hand-crafted
        // request that names an id the picker never surfaced.
        boolean anyIneligible = found.stream().anyMatch(d -> !d.isPresentable() || d.isTemplate());
        if (anyIneligible) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "One or more dashboards aren't enabled for presentations — enable \"Presentable\" from the dashboard's own edit view first");
        }
        itemRepo.deleteByPresentationId(id);
        int order = 0;
        for (Long dashboardId : ids) {
            DashboardPresentationItem item = new DashboardPresentationItem();
            item.setPresentationId(id);
            item.setDashboardId(dashboardId);
            item.setSortOrder(order++);
            itemRepo.save(item);
        }
    }

    private static int clampRotation(Integer requested) {
        int v = requested == null ? DEFAULT_ROTATION_SECONDS : requested;
        return Math.min(Math.max(v, MIN_ROTATION_SECONDS), MAX_ROTATION_SECONDS);
    }

    private static Long currentUserId() {
        try { return Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName()); }
        catch (Exception e) { return null; }
    }
}
