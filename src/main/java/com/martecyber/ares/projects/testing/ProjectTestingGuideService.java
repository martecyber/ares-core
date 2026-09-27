package com.martecyber.ares.projects.testing;

import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.kb.testing.TestingGuide;
import com.martecyber.ares.kb.testing.TestingGuidePoint;
import com.martecyber.ares.kb.testing.TestingGuidePointRepository;
import com.martecyber.ares.kb.testing.TestingGuideRepository;
import com.martecyber.ares.projects.ProjectService;
import com.martecyber.ares.projects.testing.dto.ProjectTestingGuideDto;
import com.martecyber.ares.projects.testing.dto.ProjectTestingGuideItemDto;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ProjectTestingGuideService {

    private static final Set<String> STATUSES = Set.of("pending", "done", "not_applicable");

    private final ProjectTestingGuideRepository ptgRepo;
    private final ProjectTestingGuideItemRepository itemRepo;
    private final TestingGuideRepository guideRepo;
    private final TestingGuidePointRepository pointRepo;
    private final ProjectService projectService;

    public ProjectTestingGuideService(ProjectTestingGuideRepository ptgRepo,
                                      ProjectTestingGuideItemRepository itemRepo,
                                      TestingGuideRepository guideRepo,
                                      TestingGuidePointRepository pointRepo,
                                      ProjectService projectService) {
        this.ptgRepo = ptgRepo;
        this.itemRepo = itemRepo;
        this.guideRepo = guideRepo;
        this.pointRepo = pointRepo;
        this.projectService = projectService;
    }

    public List<ProjectTestingGuideDto> list(Long projectId) {
        var guides = ptgRepo.findByProjectIdOrderByAssignedAtAsc(projectId);
        if (guides.isEmpty()) return List.of();
        var ptgIds = guides.stream().map(ProjectTestingGuide::getId).toList();
        Map<Long, List<ProjectTestingGuideItemDto>> itemsByPtg = new HashMap<>();
        for (var item : itemRepo.findByProjectTestingGuideIdIn(ptgIds)) {
            itemsByPtg.computeIfAbsent(item.getProjectTestingGuideId(), k -> new java.util.ArrayList<>())
                .add(ProjectTestingGuideItemDto.from(item));
        }
        for (var list : itemsByPtg.values()) {
            list.sort(java.util.Comparator.comparingInt(ProjectTestingGuideItemDto::sortOrder)
                .thenComparing(ProjectTestingGuideItemDto::id));
        }
        return guides.stream()
            .map(g -> ProjectTestingGuideDto.from(g, itemsByPtg.getOrDefault(g.getId(), List.of())))
            .toList();
    }

    /** Assign a KB guide to an assessment project, snapshotting its points as checklist items. */
    @Transactional
    public ProjectTestingGuideDto assign(Long projectId, Long guideId) {
        if (!projectService.isAssessmentProject(projectId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Testing guides can only be assigned to assessment projects");
        }
        TestingGuide guide = guideRepo.findById(guideId)
            .orElseThrow(() -> NotFoundException.of("testing_guide", guideId));

        OffsetDateTime now = OffsetDateTime.now();
        ProjectTestingGuide ptg = new ProjectTestingGuide();
        ptg.setProjectId(projectId);
        ptg.setGuideId(guide.getId());
        ptg.setName(guide.getName());
        ptg.setAssignedAt(now);
        ptgRepo.save(ptg);

        var points = pointRepo.findByGuideIdOrderBySortOrderAscIdAsc(guideId);
        for (TestingGuidePoint p : points) {
            itemRepo.save(newItem(ptg.getId(), p, now));
        }
        return one(ptg.getId());
    }

    /**
     * Pull new/changed KB points into an existing assignment. Existing items keyed by
     * guide_point_id keep their status/notes but refresh title/description; new points
     * are added. Items whose KB point was deleted are left untouched.
     */
    @Transactional
    public ProjectTestingGuideDto resync(Long projectId, Long ptgId) {
        ProjectTestingGuide ptg = requireOwned(projectId, ptgId);
        if (ptg.getGuideId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This assignment is no longer linked to a KB guide and cannot be re-synced");
        }
        var existing = itemRepo.findByProjectTestingGuideIdOrderBySortOrderAscIdAsc(ptgId);
        Map<Long, ProjectTestingGuideItem> byPoint = new HashMap<>();
        for (var it : existing) {
            if (it.getGuidePointId() != null) byPoint.put(it.getGuidePointId(), it);
        }
        OffsetDateTime now = OffsetDateTime.now();
        var points = pointRepo.findByGuideIdOrderBySortOrderAscIdAsc(ptg.getGuideId());
        for (TestingGuidePoint p : points) {
            ProjectTestingGuideItem it = byPoint.get(p.getId());
            if (it == null) {
                itemRepo.save(newItem(ptgId, p, now));
            } else {
                it.setTitle(p.getTitle());
                it.setDescription(p.getDescription());
                it.setSortOrder(p.getSortOrder());
                itemRepo.save(it);
            }
        }
        // Refresh snapshot name from the live guide too.
        guideRepo.findById(ptg.getGuideId()).ifPresent(g -> { ptg.setName(g.getName()); ptgRepo.save(ptg); });
        return one(ptgId);
    }

    @Transactional
    public void remove(Long projectId, Long ptgId) {
        requireOwned(projectId, ptgId);
        ptgRepo.deleteById(ptgId); // items cascade via FK
    }

    @Transactional
    public ProjectTestingGuideItemDto updateItem(Long projectId, Long ptgId, Long itemId, ItemPatch patch) {
        requireOwned(projectId, ptgId);
        ProjectTestingGuideItem item = itemRepo.findById(itemId)
            .filter(i -> i.getProjectTestingGuideId().equals(ptgId))
            .orElseThrow(() -> NotFoundException.of("project_testing_guide_item", itemId));
        if (patch.status() != null) {
            String s = patch.status().trim().toLowerCase();
            if (!STATUSES.contains(s)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid status: " + patch.status());
            }
            item.setStatus(s);
        }
        if (patch.notes() != null) {
            item.setNotes(patch.notes().isBlank() ? null : patch.notes());
        }
        item.setUpdatedAt(OffsetDateTime.now());
        item.setUpdatedBy(currentUserId());
        return ProjectTestingGuideItemDto.from(itemRepo.save(item));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private ProjectTestingGuideDto one(Long ptgId) {
        ProjectTestingGuide ptg = ptgRepo.findById(ptgId)
            .orElseThrow(() -> NotFoundException.of("project_testing_guide", ptgId));
        var items = itemRepo.findByProjectTestingGuideIdOrderBySortOrderAscIdAsc(ptgId).stream()
            .map(ProjectTestingGuideItemDto::from).toList();
        return ProjectTestingGuideDto.from(ptg, items);
    }

    private ProjectTestingGuide requireOwned(Long projectId, Long ptgId) {
        return ptgRepo.findById(ptgId)
            .filter(g -> g.getProjectId().equals(projectId))
            .orElseThrow(() -> NotFoundException.of("project_testing_guide", ptgId));
    }

    private static ProjectTestingGuideItem newItem(Long ptgId, TestingGuidePoint p, OffsetDateTime now) {
        ProjectTestingGuideItem it = new ProjectTestingGuideItem();
        it.setProjectTestingGuideId(ptgId);
        it.setGuidePointId(p.getId());
        it.setTitle(p.getTitle());
        it.setDescription(p.getDescription());
        it.setSortOrder(p.getSortOrder());
        it.setStatus("pending");
        it.setUpdatedAt(now);
        return it;
    }

    private static Long currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || "anonymousUser".equals(auth.getName())) return null;
        try { return Long.parseLong(auth.getName()); } catch (NumberFormatException e) { return null; }
    }

    public record AssignRequest(Long guideId) {}

    public record ItemPatch(String status, String notes) {}
}
