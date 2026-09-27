package com.martecyber.ares.projects;

import com.martecyber.ares.common.ConflictException;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.projects.dto.*;
import com.martecyber.ares.organizations.OrganizationRepository;
import com.martecyber.ares.users.OrgScopeService;
import com.martecyber.ares.users.UserRepository;
import com.martecyber.ares.workflows.WorkflowEventDispatcher;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ProjectService {

    private final ProjectRepository repo;
    private final ProjectScopeEntryRepository scopeRepo;
    private final ProjectMemberRepository memberRepo;
    private final ProjectTypeRepository typeRepo;
    private final OrganizationRepository orgRepo;
    private final UserRepository userRepo;
    private final ScopeEntryAssetDeriver deriver;
    private final AssetScopeClassifier classifier;
    private final ScopeClassifyScheduler scheduler;
    private final OrgScopeService orgScope;
    private final WorkflowEventDispatcher workflowEventDispatcher;

    public ProjectService(
        ProjectRepository repo,
        ProjectScopeEntryRepository scopeRepo,
        ProjectMemberRepository memberRepo,
        ProjectTypeRepository typeRepo,
        OrganizationRepository orgRepo,
        UserRepository userRepo,
        ScopeEntryAssetDeriver deriver,
        AssetScopeClassifier classifier,
        ScopeClassifyScheduler scheduler,
        OrgScopeService orgScope,
        WorkflowEventDispatcher workflowEventDispatcher
    ) {
        this.repo = repo;
        this.scopeRepo = scopeRepo;
        this.memberRepo = memberRepo;
        this.typeRepo = typeRepo;
        this.orgRepo = orgRepo;
        this.userRepo = userRepo;
        this.deriver = deriver;
        this.classifier = classifier;
        this.scheduler = scheduler;
        this.orgScope = orgScope;
        this.workflowEventDispatcher = workflowEventDispatcher;
    }

    private void scheduleAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else {
            action.run();
        }
    }

    public List<ProjectDto> schedule(LocalDate start, LocalDate end) {
        List<Project> engs = repo.findInRange(start, end);
        if (engs.isEmpty()) return List.of();

        var engIds = engs.stream().map(Project::getId).toList();
        var orgIds  = engs.stream().map(Project::getOrganizationId).collect(Collectors.toSet());

        Map<Long, String> orgNames = orgRepo.findAllById(orgIds).stream()
            .collect(Collectors.toMap(o -> o.getId(), o -> o.getName()));
        Map<Long, String> typeNames = loadTypeNames();
        Map<Long, String> typeCodes = loadTypeCodes();
        Map<Long, String> supertypeCodes = loadSupertypeCodes();

        // Load all members in one query, then group by projectId
        var allMembers = memberRepo.findByIdProjectIdIn(engIds);
        var membersByEng = allMembers.stream()
            .collect(Collectors.groupingBy(m -> m.getId().getProjectId()));

        return engs.stream().map(e -> {
            var members = resolveMembersDto(membersByEng.getOrDefault(e.getId(), List.of()));
            return toDto(e, orgNames.getOrDefault(e.getOrganizationId(), ""), typeNames, typeCodes, supertypeCodes, List.of(), members);
        }).toList();
    }

    public Page<ProjectDto> list(Long organizationId, String status, int page, int size, Authentication auth) {
        var p = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200), Sort.unsorted());
        Page<Project> res;
        if (organizationId != null) {
            orgScope.assertOrgAccess(auth, organizationId);
            res = repo.filter(organizationId, blank(status), p);
        } else if (orgScope.isPlatformAdmin(auth)) {
            res = repo.filter(null, blank(status), p);
        } else {
            var accessible = orgScope.accessibleOrgIds(auth);
            res = accessible.isEmpty() ? Page.empty(p) : repo.filterByOrgIds(List.copyOf(accessible), blank(status), p);
        }
        var orgIds = res.getContent().stream().map(Project::getOrganizationId).collect(Collectors.toSet());
        Map<Long, String> orgNames = orgRepo.findAllById(orgIds).stream()
            .collect(Collectors.toMap(o -> o.getId(), o -> o.getName()));
        Map<Long, String> typeNames = loadTypeNames();
        Map<Long, String> typeCodes = loadTypeCodes();
        Map<Long, String> supertypeCodes = loadSupertypeCodes();
        return res.map(e -> toDto(e, orgNames.getOrDefault(e.getOrganizationId(), ""), typeNames, typeCodes, supertypeCodes, List.of(), List.of()));
    }

    public ProjectDto get(Long id, Authentication auth) {
        orgScope.assertProjectAccess(auth, id);
        Project e = repo.findById(id).orElseThrow(() -> NotFoundException.of("project", id));
        String orgName = orgRepo.findById(e.getOrganizationId()).map(o -> o.getName()).orElse("");
        Map<Long, String> typeNames = loadTypeNames();
        Map<Long, String> typeCodes = loadTypeCodes();
        Map<Long, String> supertypeCodes = loadSupertypeCodes();
        List<ScopeEntryDto> scope = scopeRepo.findByProjectIdOrderByCreatedAtAsc(id).stream()
            .map(ProjectService::toScopeDto).toList();
        List<ProjectDto.MemberDto> members = resolveMembersDto(memberRepo.findByIdProjectId(id));
        return toDto(e, orgName, typeNames, typeCodes, supertypeCodes, scope, members);
    }

    @Transactional
    public ProjectDto create(CreateProjectRequest req) {
        var org = orgRepo.findById(req.organizationId())
            .orElseThrow(() -> NotFoundException.of("organization", req.organizationId()));

        ProjectType type = null;
        if (req.typeId() != null) {
            type = typeRepo.findById(req.typeId())
                .orElseThrow(() -> NotFoundException.of("project_type", req.typeId()));
        }
        if (isRetestType(type) && (req.startDate() == null || req.endDate() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Retesting projects require both a start and end date");
        }

        OffsetDateTime now = OffsetDateTime.now();
        String code = (req.code() != null && !req.code().isBlank())
            ? req.code().trim().toUpperCase()
            : generateProjectCode(org.getSlug(), type, req.organizationId(), now.getYear());

        Project e = new Project();
        e.setOrganizationId(req.organizationId());
        e.setName(req.name().trim());
        e.setCode(code);
        e.setTypeId(req.typeId());
        e.setStartDate(req.startDate());
        e.setEndDate(req.endDate());
        e.setOwnerUserId(req.ownerUserId());
        e.setIterationCadence(req.iterationCadence());
        e.setAutoAdvanceIterations(req.autoAdvanceIterations());
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        repo.save(e);
        workflowEventDispatcher.onProjectCreated(e);

        Map<Long, String> typeNames = loadTypeNames();
        Map<Long, String> typeCodes = loadTypeCodes();
        Map<Long, String> supertypeCodes = loadSupertypeCodes();
        return toDto(e, org.getName(), typeNames, typeCodes, supertypeCodes, List.of(), List.of());
    }

    public String previewCode(Long organizationId, Long typeId) {
        var org = orgRepo.findById(organizationId).orElse(null);
        String slug = org != null ? org.getSlug() : null;
        ProjectType type = typeId != null ? typeRepo.findById(typeId).orElse(null) : null;
        int year = java.time.Year.now().getValue();
        return generateProjectCode(slug, type, organizationId, year);
    }

    @Transactional
    public ProjectDto update(Long id, UpdateProjectRequest req) {
        Project e = repo.findById(id).orElseThrow(() -> NotFoundException.of("project", id));
        if (req.name() != null && !req.name().isBlank()) e.setName(req.name().trim());
        if (req.typeId() != null) e.setTypeId(req.typeId());
        if (req.startDate() != null) e.setStartDate(req.startDate());
        if (req.endDate() != null) e.setEndDate(req.endDate());
        if (req.ownerUserId() != null) e.setOwnerUserId(req.ownerUserId() == 0L ? null : req.ownerUserId());
        // iterationCadence: explicit null clears it; non-null overwrites
        if (req.iterationCadence() != null || e.getIterationCadence() != null)
            e.setIterationCadence(req.iterationCadence());
        if (req.autoAdvanceIterations() != null) e.setAutoAdvanceIterations(req.autoAdvanceIterations());
        if (req.clientsCanViewDetections() != null) e.setClientsCanViewDetections(req.clientsCanViewDetections());

        ProjectType effectiveType = e.getTypeId() != null ? typeRepo.findById(e.getTypeId()).orElse(null) : null;
        if (isRetestType(effectiveType) && (e.getStartDate() == null || e.getEndDate() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Retesting projects require both a start and end date");
        }

        e.setUpdatedAt(OffsetDateTime.now());
        repo.save(e);
        workflowEventDispatcher.onProjectUpdated(e);
        String orgName = orgRepo.findById(e.getOrganizationId()).map(o -> o.getName()).orElse("");
        Map<Long, String> typeNames = loadTypeNames();
        Map<Long, String> typeCodes = loadTypeCodes();
        Map<Long, String> supertypeCodes = loadSupertypeCodes();
        List<ScopeEntryDto> scope = scopeRepo.findByProjectIdOrderByCreatedAtAsc(id).stream()
            .map(ProjectService::toScopeDto).toList();
        List<ProjectDto.MemberDto> members = resolveMembersDto(memberRepo.findByIdProjectId(id));
        return toDto(e, orgName, typeNames, typeCodes, supertypeCodes, scope, members);
    }

    @Transactional
    public ProjectDto complete(Long id) {
        Project e = repo.findById(id).orElseThrow(() -> NotFoundException.of("project", id));
        if (e.getCompletedAt() != null) throw new ConflictException("Project is already completed");
        e.setCompletedAt(OffsetDateTime.now());
        e.setUpdatedAt(OffsetDateTime.now());
        repo.save(e);
        return get(id, SecurityContextHolder.getContext().getAuthentication());
    }

    @Transactional
    public ProjectDto reopen(Long id) {
        Project e = repo.findById(id).orElseThrow(() -> NotFoundException.of("project", id));
        if (e.getCompletedAt() == null) throw new ConflictException("Project is not completed");
        e.setCompletedAt(null);
        e.setUpdatedAt(OffsetDateTime.now());
        repo.save(e);
        return get(id, SecurityContextHolder.getContext().getAuthentication());
    }

    /** Manually approves the jump to the calendar's current iteration for a MONITOR project
     *  whose iterations don't auto-advance. Harmless/idempotent if already in sync. */
    @Transactional
    public ProjectDto approveIteration(Long id) {
        Project e = repo.findById(id).orElseThrow(() -> NotFoundException.of("project", id));
        if (e.getIterationCadence() == null)
            throw new IllegalArgumentException("Project has no iteration cadence configured");
        e.setActiveIterationLabel(MonitorIterationHelper.computeLabel(e.getIterationCadence(), java.time.LocalDate.now()));
        e.setUpdatedAt(OffsetDateTime.now());
        repo.save(e);
        return get(id, SecurityContextHolder.getContext().getAuthentication());
    }

    @Transactional
    public void delete(Long id) {
        Project e = repo.findById(id).orElseThrow(() -> NotFoundException.of("project", id));
        repo.deleteById(id);
        workflowEventDispatcher.onProjectDeleted(id, e.getOrganizationId());
    }

    @Transactional
    public List<ScopeEntryDto> addScopeEntries(Long projectId, AddScopeEntryRequest req) {
        Project project = repo.findById(projectId)
            .orElseThrow(() -> NotFoundException.of("project", projectId));

        List<ScopeNormalizer.NormalizedEntry> normalized =
            ScopeNormalizer.normalize(req.kind(), req.value());
        if (normalized.isEmpty()) return List.of();

        boolean inScope = req.inScope() == null || req.inScope();
        OffsetDateTime now = OffsetDateTime.now();
        List<ScopeEntryDto> results = new ArrayList<>();

        for (ScopeNormalizer.NormalizedEntry ne : normalized) {
            ProjectScopeEntry entry = new ProjectScopeEntry();
            entry.setProjectId(projectId);
            entry.setKind(ne.kind());
            entry.setValue(ne.value());
            entry.setNotes(req.notes());
            entry.setMetadata(req.metadata());
            entry.setInScope(inScope);
            entry.setCreatedAt(now);
            entry.setUpdatedAt(now);
            ProjectScopeEntry saved = scopeRepo.save(entry);
            deriver.derive(saved, project.getOrganizationId());
            results.add(toScopeDto(saved));
        }

        scheduleAfterCommit(() -> scheduler.schedule(projectId));
        return results;
    }

    @Transactional
    public void removeScopeEntry(Long projectId, Long entryId) {
        if (!repo.existsById(projectId)) throw NotFoundException.of("project", projectId);
        scopeRepo.deleteByProjectIdAndId(projectId, entryId);
        scheduleAfterCommit(() -> scheduler.schedule(projectId));
    }

    @Transactional
    public ProjectDto.MemberDto addMember(Long projectId, AddMemberRequest req) {
        if (!repo.existsById(projectId)) throw NotFoundException.of("project", projectId);
        String role = (req.role() == null || req.role().isBlank()) ? "operator" : req.role();
        var key = new ProjectMemberId(projectId, req.userId(), role);
        if (memberRepo.existsById(key)) throw new ConflictException("User already has this role in this project");
        var user = userRepo.findById(req.userId()).orElseThrow(() -> NotFoundException.of("user", req.userId()));
        if ("lead".equals(role)) {
            boolean alreadyHasLead = memberRepo.findByIdProjectId(projectId)
                .stream().anyMatch(m -> "lead".equals(m.getRole()));
            if (alreadyHasLead) throw new ConflictException("Project already has a lead assigned");
        }
        ProjectMember m = new ProjectMember(projectId, req.userId(), role);
        memberRepo.save(m);
        return new ProjectDto.MemberDto(user.getId(), user.getEmail(), user.getDisplayName(), role, m.getAddedAt());
    }

    @Transactional
    public void removeMember(Long projectId, Long userId, String role) {
        var key = new ProjectMemberId(projectId, userId, role);
        if (!memberRepo.existsById(key)) throw NotFoundException.of("project member", userId);
        memberRepo.deleteById(key);
    }

    /** Returns the current user's role on the project: "admin", "lead", "operator", or null if no access. */
    public String getMyProjectRole(Long projectId) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        boolean isAdmin = auth.getAuthorities().stream()
            .anyMatch(a -> "ROLE_MSSP_ADMIN".equals(a.getAuthority()));
        if (isAdmin) return "admin";
        Long userId = null;
        try { userId = Long.parseLong(auth.getName()); } catch (NumberFormatException ignored) {}
        if (userId == null) return null;
        final Long uid = userId;
        java.util.List<ProjectMember> userEntries = memberRepo.findByIdProjectId(projectId)
            .stream().filter(m -> uid.equals(m.getId().getUserId())).toList();
        if (userEntries.stream().anyMatch(m -> "lead".equals(m.getRole()))) return "lead";
        if (userEntries.stream().anyMatch(m -> "operator".equals(m.getRole()))) return "operator";
        return null;
    }

    // ── Helpers ────────────────────────────────────────────────────────

    /** Computes the project status from dates and explicit completion. */
    public static String computeStatus(Project e) {
        if (e.getCompletedAt() != null) return "completed";
        LocalDate today = LocalDate.now();
        LocalDate start = e.getStartDate();
        LocalDate end = e.getEndDate();
        if (start == null || start.isAfter(today)) return "scheduled";
        if (end != null && end.isBefore(today)) return "past_due";
        return "active";
    }

    private String generateProjectCode(String orgSlug, ProjectType type, Long orgId, int year) {
        String orgPart = orgSlug == null ? "ORG" : orgSlug.toUpperCase().replaceAll("[^A-Z0-9]", "");
        String typePart = (type != null) ? type.getCode() : "GEN";

        if (isContinuousNumberingType(type) && !isMonitoringType(type)) {
            // Continuous-numbering types (e.g. bughunting's "BH") are never year-scoped.
            long seq = repo.countByOrgAndTypeCode(orgId, typePart) + 1;
            return String.format("%s-%s-%02d", orgPart, typePart, seq);
        }

        if (isMonitoringType(type)) {
            if ("MONITOR".equals(type.getCode())) {
                // Root MONITOR: shared sequential across entire MONITOR family.
                long seq = repo.countByOrgMonitorType(orgId) + 1;
                return String.format("%s-MONITOR-%02d", orgPart, seq);
            } else {
                // User-defined MONITOR subtype (e.g. EASM): own code + per-type count.
                long seq = repo.countByOrgAndTypeCode(orgId, typePart) + 1;
                return String.format("%s-%s-%02d", orgPart, typePart, seq);
            }
        }

        String yy = String.valueOf(year).substring(2);
        long seq = repo.countByOrgYear(orgId, year) + 1;
        return String.format("%s-%s-%s-%02d", orgPart, typePart, yy, seq);
    }

    /** Returns true when the type itself (or its immediate supertype) opted into continuous,
     *  year-less numbering via {@code project_type.continuous_numbering} — data-driven so a
     *  plugin-provided type (e.g. bughunting's "BH", set via {@link ProjectTypeFacade#ensure})
     *  gets the same treatment as a core-owned one without ares-core needing to know its code. */
    private boolean isContinuousNumberingType(ProjectType type) {
        if (type == null) return false;
        if (type.isContinuousNumbering()) return true;
        if (type.getSupertypeId() != null) {
            return typeRepo.findById(type.getSupertypeId())
                .map(ProjectType::isContinuousNumbering)
                .orElse(false);
        }
        return false;
    }

    /** Returns true when the type is the MONITOR master or any user-defined subtype of it. */
    public boolean isMonitoringType(ProjectType type) {
        if (type == null) return false;
        if ("MONITOR".equals(type.getCode())) return true;
        if (type.getSupertypeId() != null) {
            return typeRepo.findById(type.getSupertypeId())
                .map(t -> "MONITOR".equals(t.getCode()))
                .orElse(false);
        }
        return false;
    }

    /** Resolves whether a project (by id) is a MONITOR-type project. */
    public boolean isMonitorProject(Long projectId) {
        return repo.findById(projectId)
            .flatMap(p -> p.getTypeId() != null ? typeRepo.findById(p.getTypeId()) : java.util.Optional.empty())
            .map(this::isMonitoringType)
            .orElse(false);
    }

    /** Returns true when the type is the ASSESS master or any user-defined subtype of it. */
    public boolean isAssessmentType(ProjectType type) {
        if (type == null) return false;
        if ("ASSESS".equals(type.getCode())) return true;
        if (type.getSupertypeId() != null) {
            return typeRepo.findById(type.getSupertypeId())
                .map(t -> "ASSESS".equals(t.getCode()))
                .orElse(false);
        }
        return false;
    }

    /** Resolves whether a project (by id) is an ASSESS-type project. */
    public boolean isAssessmentProject(Long projectId) {
        return repo.findById(projectId)
            .flatMap(p -> p.getTypeId() != null ? typeRepo.findById(p.getTypeId()) : java.util.Optional.empty())
            .map(this::isAssessmentType)
            .orElse(false);
    }

    /** Returns true when the type is the RETEST master or any user-defined subtype of it. */
    public boolean isRetestType(ProjectType type) {
        if (type == null) return false;
        if ("RETEST".equals(type.getCode())) return true;
        if (type.getSupertypeId() != null) {
            return typeRepo.findById(type.getSupertypeId())
                .map(t -> "RETEST".equals(t.getCode()))
                .orElse(false);
        }
        return false;
    }

    /** Resolves whether a project (by id) is a RETEST-type project. */
    public boolean isRetestProject(Long projectId) {
        return repo.findById(projectId)
            .flatMap(p -> p.getTypeId() != null ? typeRepo.findById(p.getTypeId()) : java.util.Optional.empty())
            .map(this::isRetestType)
            .orElse(false);
    }

    private List<ProjectDto.MemberDto> resolveMembersDto(List<ProjectMember> members) {
        if (members.isEmpty()) return List.of();
        var userIds = members.stream().map(m -> m.getId().getUserId()).toList();
        Map<Long, com.martecyber.ares.users.User> usersById = userRepo.findAllById(userIds).stream()
            .collect(Collectors.toMap(u -> u.getId(), u -> u));
        return members.stream().map(m -> {
            var u = usersById.get(m.getId().getUserId());
            String email = u != null ? u.getEmail() : "";
            String name = u != null ? u.getDisplayName() : "";
            return new ProjectDto.MemberDto(m.getId().getUserId(), email, name, m.getRole(), m.getAddedAt());
        }).toList();
    }

    private Map<Long, String> loadTypeNames() {
        return typeRepo.findAll().stream()
            .collect(Collectors.toMap(t -> t.getId(), t -> t.getName()));
    }

    private Map<Long, String> loadTypeCodes() {
        return typeRepo.findAll().stream()
            .collect(Collectors.toMap(t -> t.getId(), t -> t.getCode()));
    }

    /** Maps type id → parent type's code (absent for root types). */
    private Map<Long, String> loadSupertypeCodes() {
        List<ProjectType> all = typeRepo.findAll();
        Map<Long, String> codeById = all.stream()
            .collect(Collectors.toMap(t -> t.getId(), t -> t.getCode()));
        return all.stream()
            .filter(t -> t.getSupertypeId() != null)
            .collect(Collectors.toMap(t -> t.getId(),
                t -> codeById.getOrDefault(t.getSupertypeId(), null)));
    }

    private static ProjectDto toDto(Project e, String orgName, Map<Long, String> typeNames,
                                        Map<Long, String> typeCodes,
                                        Map<Long, String> supertypeCodes,
                                        List<ScopeEntryDto> scope, List<ProjectDto.MemberDto> members) {
        return new ProjectDto(
            e.getId(), e.getOrganizationId(), orgName,
            e.getName(), e.getCode(),
            e.getTypeId(),
            e.getTypeId() != null ? typeNames.getOrDefault(e.getTypeId(), "") : null,
            e.getTypeId() != null ? typeCodes.getOrDefault(e.getTypeId(), null) : null,
            e.getTypeId() != null ? supertypeCodes.getOrDefault(e.getTypeId(), null) : null,
            computeStatus(e),
            e.getStartDate(), e.getEndDate(), e.getCompletedAt(), e.getOwnerUserId(),
            scope, members, e.getCreatedAt(), e.getUpdatedAt(), e.getIterationCadence(),
            e.isAutoAdvanceIterations(), e.isClientsCanViewDetections()
        );
    }

    public static ScopeEntryDto toScopeDto(ProjectScopeEntry s) {
        return new ScopeEntryDto(s.getId(), s.getProjectId(), s.getKind(), s.getValue(),
            s.getNotes(), s.getMetadata(), s.isInScope(), s.getSource(), s.getExternalId(),
            s.getCreatedAt(), s.getUpdatedAt(), s.getPlatformCreatedAt(), s.getPlatformUpdatedAt());
    }

    private static String blank(String s) { return (s == null || s.isBlank()) ? null : s; }
}
