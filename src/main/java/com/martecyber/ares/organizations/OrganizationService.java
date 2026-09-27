package com.martecyber.ares.organizations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.martecyber.ares.common.ConflictException;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.projects.Project;
import com.martecyber.ares.projects.ProjectRepository;
import com.martecyber.ares.findings.Finding;
import com.martecyber.ares.findings.FindingRepository;
import com.martecyber.ares.findings.FindingStatusRepository;
import com.martecyber.ares.organizations.dto.*;
import com.martecyber.ares.workflows.WorkflowEventDispatcher;
import jakarta.transaction.Transactional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

@Service
public class OrganizationService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OrganizationRepository repo;
    private final ProjectRepository projectRepo;
    private final FindingRepository findingRepo;
    private final FindingStatusRepository findingStatusRepo;
    private final WorkflowEventDispatcher workflowEventDispatcher;

    public OrganizationService(OrganizationRepository repo,
                               ProjectRepository projectRepo,
                               FindingRepository findingRepo,
                               FindingStatusRepository findingStatusRepo,
                               WorkflowEventDispatcher workflowEventDispatcher) {
        this.repo = repo;
        this.projectRepo = projectRepo;
        this.findingRepo = findingRepo;
        this.findingStatusRepo = findingStatusRepo;
        this.workflowEventDispatcher = workflowEventDispatcher;
    }

    public Page<OrganizationDto> list(String status, int page, int size, Long operatorUserId) {
        Pageable p = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200));
        if (operatorUserId != null) {
            String statusFilter = (status == null || status.isBlank()) ? null : status;
            return repo.findByOperatorUser(operatorUserId, statusFilter, p).map(OrganizationService::toDto);
        }
        Page<Organization> res = (status == null || status.isBlank())
            ? repo.findAllByOrderByCreatedAtDesc(p)
            : repo.findByStatusOrderByCreatedAtDesc(status, p);
        return res.map(OrganizationService::toDto);
    }

    public OrganizationDto get(Long id) {
        return toDto(repo.findById(id).orElseThrow(() -> NotFoundException.of("organization", id)));
    }

    @Transactional
    public OrganizationDto create(CreateOrganizationRequest req) {
        if (repo.existsBySlugIgnoreCase(req.slug())) {
            throw new ConflictException("Organization slug '" + req.slug() + "' already exists");
        }
        Organization o = new Organization();
        o.setName(req.name().trim());
        o.setSlug(req.slug().toLowerCase());
        o.setStatus("active");
        o.setSettings(req.settings());
        OffsetDateTime now = OffsetDateTime.now();
        o.setCreatedAt(now);
        o.setUpdatedAt(now);
        Organization saved = repo.save(o);
        workflowEventDispatcher.onOrganizationCreated(saved);
        return toDto(saved);
    }

    @Transactional
    public OrganizationDto update(Long id, UpdateOrganizationRequest req) {
        Organization o = repo.findById(id).orElseThrow(() -> NotFoundException.of("organization", id));
        if (req.name() != null && !req.name().isBlank()) o.setName(req.name().trim());
        if (req.status() != null && !req.status().isBlank()) o.setStatus(req.status());
        if (req.settings() != null) o.setSettings(req.settings());
        if (req.sla() != null) o.setSettings(mergeSla(o.getSettings(), req.sla()));
        o.setUpdatedAt(OffsetDateTime.now());
        Organization saved = repo.save(o);
        workflowEventDispatcher.onOrganizationUpdated(saved);
        return toDto(saved);
    }

    /**
     * Restricted update for CLIENT_ADMIN accounts: name and SLA only. Ignores {@code status}
     * (archiving) and raw {@code settings} even if a caller supplies them — client admins can
     * never archive their own organization or touch anything beyond their profile fields.
     */
    @Transactional
    public OrganizationDto updateProfile(Long id, UpdateOrganizationRequest req) {
        Organization o = repo.findById(id).orElseThrow(() -> NotFoundException.of("organization", id));
        if (req.name() != null && !req.name().isBlank()) o.setName(req.name().trim());
        if (req.sla() != null) o.setSettings(mergeSla(o.getSettings(), req.sla()));
        o.setUpdatedAt(OffsetDateTime.now());
        Organization saved = repo.save(o);
        workflowEventDispatcher.onOrganizationUpdated(saved);
        return toDto(saved);
    }

    // ── Logo management ───────────────────────────────────────────────────────

    @Transactional
    public void uploadLogo(Long id, MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("File is empty");
        if (file.getSize() > 2 * 1024 * 1024) throw new IllegalArgumentException("Logo must be under 2 MB");
        String mime = file.getContentType();
        if (mime == null || !mime.startsWith("image/")) throw new IllegalArgumentException("File must be an image");
        Organization o = repo.findById(id).orElseThrow(() -> NotFoundException.of("organization", id));
        o.setLogoData(file.getBytes());
        o.setLogoMime(mime);
        o.setUpdatedAt(OffsetDateTime.now());
        repo.save(o);
    }

    @Transactional
    public void deleteLogo(Long id) {
        Organization o = repo.findById(id).orElseThrow(() -> NotFoundException.of("organization", id));
        o.setLogoData(null);
        o.setLogoMime(null);
        o.setUpdatedAt(OffsetDateTime.now());
        repo.save(o);
    }

    public record LogoData(byte[] data, String mime) {}

    public LogoData getLogo(Long id) {
        Organization o = repo.findById(id).orElseThrow(() -> NotFoundException.of("organization", id));
        if (o.getLogoData() == null) throw new NotFoundException("Logo not found for organization " + id);
        return new LogoData(o.getLogoData(), o.getLogoMime() != null ? o.getLogoMime() : "image/png");
    }

    @Transactional
    public void archive(Long id) {
        Organization o = repo.findById(id).orElseThrow(() -> NotFoundException.of("organization", id));
        o.setStatus("archived");
        o.setUpdatedAt(OffsetDateTime.now());
        repo.save(o);
    }

    // ── Dashboard ─────────────────────────────────────────────────────────────

    public OrgDashboardDto getDashboard(Long orgId) {
        Organization org = repo.findById(orgId).orElseThrow(() -> NotFoundException.of("organization", orgId));
        OrganizationDto.SlaSettings sla = parseSla(org.getSettings());

        // Active projects
        List<Project> activeEngs = projectRepo.findActiveByOrg(orgId);
        List<OrgDashboardDto.ActiveProject> engDtos = activeEngs.stream()
            .map(e -> new OrgDashboardDto.ActiveProject(
                e.getId(), e.getName(), e.getCode(),
                com.martecyber.ares.projects.ProjectService.computeStatus(e),
                e.getStartDate()))
            .toList();

        // Published open findings for this org
        List<Finding> openFindings = findingRepo.findOpenPublishedByOrgId(orgId);

        LocalDate today  = LocalDate.now();
        LocalDate in7    = today.plusDays(7);

        long critical  = openFindings.stream().filter(f -> "critical".equals(f.getSeverity())).count();
        long high      = openFindings.stream().filter(f -> "high".equals(f.getSeverity())).count();
        long medium    = openFindings.stream().filter(f -> "medium".equals(f.getSeverity())).count();
        long low       = openFindings.stream().filter(f -> "low".equals(f.getSeverity())).count();

        long dueIn7Days = openFindings.stream()
            .filter(f -> { LocalDate d = deadline(f, sla); return d != null && !d.isBefore(today) && !d.isAfter(in7); })
            .count();
        long slaPassed = openFindings.stream()
            .filter(f -> { LocalDate d = deadline(f, sla); return d != null && d.isBefore(today); })
            .count();

        // 10 findings with soonest deadline
        java.util.Map<Long, String> statusNames = loadStatusNames();
        List<OrgDashboardDto.UrgentFinding> urgent = openFindings.stream()
            .filter(f -> deadline(f, sla) != null)
            .sorted(Comparator.comparing(f -> deadline(f, sla)))
            .limit(10)
            .map(f -> new OrgDashboardDto.UrgentFinding(
                f.getId(), f.getCode(), f.getTitle(), f.getSeverity(),
                statusNames.getOrDefault(f.getStatusId(), ""),
                f.getProjectId(), deadline(f, sla)))
            .toList();

        return new OrgDashboardDto(
            new OrgDashboardDto.Stats(critical, high, medium, low, dueIn7Days, slaPassed),
            engDtos, urgent);
    }

    private LocalDate deadline(Finding f, OrganizationDto.SlaSettings sla) {
        // Explicit manual deadline takes priority
        if (f.getDueDate() != null) return f.getDueDate();
        // Fall back to SLA-computed deadline
        if (f.getReportedAt() == null) return null;
        int days = sla.forSeverity(f.getSeverity());
        if (days <= 0) return null;
        return f.getReportedAt().toLocalDate().plusDays(days);
    }

    private java.util.Map<Long, String> loadStatusNames() {
        return findingStatusRepo.findAll().stream()
            .collect(java.util.stream.Collectors.toMap(
                com.martecyber.ares.findings.FindingStatus::getId,
                com.martecyber.ares.findings.FindingStatus::getName));
    }

    // ── SLA helpers ───────────────────────────────────────────────────────────

    // Public (not package-private) so AqlVariableExpander (aql.compile package) can reuse this
    // exact parsing instead of duplicating it for the p0_sla_period.. p4_sla_period AQL variables.
    public static OrganizationDto.SlaSettings parseSla(String settingsJson) {
        if (settingsJson == null || settingsJson.isBlank()) return OrganizationDto.SlaSettings.defaults();
        try {
            JsonNode root = MAPPER.readTree(settingsJson);
            JsonNode sla = root.get("sla");
            if (sla == null) return OrganizationDto.SlaSettings.defaults();
            return new OrganizationDto.SlaSettings(
                sla.path("critical").asInt(7),
                sla.path("high").asInt(30),
                sla.path("medium").asInt(90),
                sla.path("low").asInt(180),
                sla.path("info").asInt(0));
        } catch (Exception e) { return OrganizationDto.SlaSettings.defaults(); }
    }

    private static String mergeSla(String existing, UpdateOrganizationRequest.SlaSettings sla) {
        try {
            ObjectNode root = existing != null && !existing.isBlank()
                ? (ObjectNode) MAPPER.readTree(existing) : MAPPER.createObjectNode();
            OrganizationDto.SlaSettings prev = parseSla(existing);
            ObjectNode slaNode = MAPPER.createObjectNode();
            slaNode.put("critical", sla.critical() != null ? sla.critical() : prev.critical());
            slaNode.put("high",     sla.high()     != null ? sla.high()     : prev.high());
            slaNode.put("medium",   sla.medium()   != null ? sla.medium()   : prev.medium());
            slaNode.put("low",      sla.low()       != null ? sla.low()       : prev.low());
            slaNode.put("info",     sla.info()      != null ? sla.info()      : prev.info());
            root.set("sla", slaNode);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) { return existing; }
    }

    // ── DTO mapping ───────────────────────────────────────────────────────────

    static OrganizationDto toDto(Organization o) {
        return new OrganizationDto(
            o.getId(), o.getName(), o.getSlug(), o.getStatus(),
            o.getSettings(), o.getLogoData() != null,
            parseSla(o.getSettings()),
            o.getCreatedAt(), o.getUpdatedAt()
        );
    }
}
