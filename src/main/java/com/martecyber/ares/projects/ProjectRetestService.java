package com.martecyber.ares.projects;

import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.findings.FindingRepository;
import com.martecyber.ares.findings.FindingService;
import com.martecyber.ares.findings.dto.FindingDto;
import com.martecyber.ares.projects.dto.ProjectRetestFindingDto;
import com.martecyber.ares.users.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Manages which existing findings (owned by their own, non-RETEST project) are in scope
 * for a RETEST project's verification pass. This is a pure membership link — the finding
 * itself, its affected-asset status and its detections are never duplicated or forked;
 * they're read/edited through the finding's own existing endpoints exactly as in its
 * origin project.
 */
@Service
public class ProjectRetestService {

    private final ProjectRetestFindingRepository linkRepo;
    private final ProjectRepository projectRepo;
    private final ProjectTypeRepository typeRepo;
    private final FindingRepository findingRepo;
    private final FindingService findingService;
    private final UserRepository userRepo;

    public ProjectRetestService(ProjectRetestFindingRepository linkRepo,
                                ProjectRepository projectRepo,
                                ProjectTypeRepository typeRepo,
                                FindingRepository findingRepo,
                                FindingService findingService,
                                UserRepository userRepo) {
        this.linkRepo = linkRepo;
        this.projectRepo = projectRepo;
        this.typeRepo = typeRepo;
        this.findingRepo = findingRepo;
        this.findingService = findingService;
        this.userRepo = userRepo;
    }

    public List<ProjectRetestFindingDto> list(Long retestProjectId) {
        requireRetestProject(retestProjectId);
        List<ProjectRetestFinding> links = linkRepo.findByRetestProjectIdOrderByLinkedAtDesc(retestProjectId);
        if (links.isEmpty()) return List.of();

        Map<Long, String> userNames = userRepo.findAllById(links.stream()
                .map(ProjectRetestFinding::getLinkedBy).filter(java.util.Objects::nonNull).toList())
            .stream().collect(Collectors.toMap(u -> u.getId(), u -> u.getDisplayName()));

        return links.stream().map(link -> {
            FindingDto finding = findingService.get(link.getFindingId());
            Project origin = projectRepo.findById(finding.projectId()).orElse(null);
            return new ProjectRetestFindingDto(
                link.getId(), link.getLinkedAt(),
                link.getLinkedBy() != null ? userNames.get(link.getLinkedBy()) : null,
                finding.projectId(),
                origin != null ? origin.getName() : null,
                origin != null ? origin.getCode() : null,
                finding
            );
        }).toList();
    }

    /** Findings eligible to be added to this retest's scope: same org, not a draft, not already linked. */
    public Page<FindingDto> searchCandidates(Long retestProjectId, String severity, int page, int size) {
        Project retestProject = requireRetestProject(retestProjectId);
        Set<Long> alreadyLinked = linkRepo.findByRetestProjectIdOrderByLinkedAtDesc(retestProjectId).stream()
            .map(ProjectRetestFinding::getFindingId).collect(Collectors.toSet());

        var p = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200));
        var results = findingRepo.filter(null, null, retestProject.getOrganizationId(), null, false,
            severity != null ? java.util.List.of(severity) : null, null, null, null, p);
        var filtered = results.getContent().stream()
            .filter(f -> !alreadyLinked.contains(f.getId()))
            .map(f -> findingService.get(f.getId()))
            .toList();
        return new org.springframework.data.domain.PageImpl<>(filtered, p, results.getTotalElements());
    }

    @Transactional
    public void link(Long retestProjectId, List<Long> findingIds) {
        requireRetestProject(retestProjectId);
        if (findingIds == null || findingIds.isEmpty()) return;

        Long userId = currentUserId();
        OffsetDateTime now = OffsetDateTime.now();
        for (Long findingId : findingIds.stream().distinct().toList()) {
            if (linkRepo.existsByRetestProjectIdAndFindingId(retestProjectId, findingId)) continue;
            if (!findingRepo.existsById(findingId)) throw NotFoundException.of("finding", findingId);
            ProjectRetestFinding link = new ProjectRetestFinding();
            link.setRetestProjectId(retestProjectId);
            link.setFindingId(findingId);
            link.setLinkedBy(userId);
            link.setLinkedAt(now);
            linkRepo.save(link);
        }
    }

    @Transactional
    public void unlink(Long retestProjectId, Long findingId) {
        requireRetestProject(retestProjectId);
        linkRepo.deleteByRetestProjectIdAndFindingId(retestProjectId, findingId);
    }

    private Project requireRetestProject(Long projectId) {
        Project project = projectRepo.findById(projectId)
            .orElseThrow(() -> NotFoundException.of("project", projectId));
        ProjectType type = project.getTypeId() != null ? typeRepo.findById(project.getTypeId()).orElse(null) : null;
        if (!isRetestType(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Project " + projectId + " is not a Retesting project");
        }
        return project;
    }

    private boolean isRetestType(ProjectType type) {
        if (type == null) return false;
        if ("RETEST".equals(type.getCode())) return true;
        if (type.getSupertypeId() == null) return false;
        return typeRepo.findById(type.getSupertypeId())
            .map(t -> "RETEST".equals(t.getCode()))
            .orElse(false);
    }

    private Long currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || "anonymousUser".equals(auth.getName())) return null;
        try { return Long.parseLong(auth.getName()); } catch (NumberFormatException e) { return null; }
    }
}
