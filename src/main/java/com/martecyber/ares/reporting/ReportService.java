package com.martecyber.ares.reporting;

import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.reporting.dto.CreateReportRequest;
import com.martecyber.ares.reporting.dto.ReportDto;
import com.martecyber.ares.storage.StorageService;
import com.martecyber.ares.users.OrgScopeService;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;

@Service
public class ReportService {

    private final ReportRepository repo;
    private final ReportFindingRepository findingLinkRepo;
    private final ReportFieldRepository fieldRepo;
    private final StorageService storage;
    private final OrgScopeService orgScope;

    @Value("${ares.storage.s3.buckets.exports}") private String exportsBucket;

    public ReportService(ReportRepository repo,
                         ReportFindingRepository findingLinkRepo,
                         ReportFieldRepository fieldRepo,
                         StorageService storage,
                         OrgScopeService orgScope) {
        this.repo = repo;
        this.findingLinkRepo = findingLinkRepo;
        this.fieldRepo = fieldRepo;
        this.storage = storage;
        this.orgScope = orgScope;
    }

    private static Authentication currentAuth() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    /** True for both client tiers (CLIENT_USER and CLIENT_ADMIN) — neither can see unpublished reports. */
    private static boolean isClientUser(Authentication auth) {
        return auth != null && auth.getAuthorities().stream()
            .anyMatch(a -> "ROLE_CLIENT_USER".equals(a.getAuthority()) || "ROLE_CLIENT_ADMIN".equals(a.getAuthority()));
    }

    public Page<ReportDto> list(Long organizationId, Long projectId, String status, int page, int size) {
        var p = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200));
        var auth = currentAuth();
        boolean clientUser = isClientUser(auth);
        String effectiveStatus = clientUser ? "published" : blank(status);

        java.util.Collection<Long> orgIds = null;
        if (organizationId != null) {
            orgScope.assertOrgAccess(auth, organizationId);
        } else if (projectId != null) {
            orgScope.assertProjectAccess(auth, projectId);
        } else if (!orgScope.isPlatformAdmin(auth)) {
            orgIds = orgScope.accessibleOrgIds(auth);
            if (orgIds.isEmpty()) return new PageImpl<>(List.of(), p, 0);
        }
        return repo.filter(organizationId, orgIds, projectId, effectiveStatus, p).map(r -> toDto(r));
    }

    public ReportDto get(Long id) {
        Report r = repo.findById(id).orElseThrow(() -> NotFoundException.of("report", id));
        var auth = currentAuth();
        orgScope.assertOrgAccess(auth, r.getOrganizationId());
        if (isClientUser(auth) && !"published".equals(r.getStatus())) {
            throw NotFoundException.of("report", id);
        }
        return toDto(r);
    }

    @Transactional
    public ReportDto create(CreateReportRequest req) {
        orgScope.assertOrgAccess(currentAuth(), req.organizationId());
        Report r = new Report();
        r.setOrganizationId(req.organizationId());
        r.setProjectId(req.projectId());
        r.setType(req.type());
        r.setFormat(req.format() != null ? req.format() : "pdf");
        r.setTitle(req.title());
        r.setGeneratedBy(resolveUserId());
        r.setCreatedAt(OffsetDateTime.now());
        return toDto(repo.save(r));
    }

    @Transactional
    public ReportDto publish(Long id) {
        Report r = repo.findById(id).orElseThrow(() -> NotFoundException.of("report", id));
        orgScope.assertOrgAccess(currentAuth(), r.getOrganizationId());
        if (!"draft".equals(r.getStatus()) && !"completed".equals(r.getStatus()))
            throw new IllegalStateException("Only draft reports can be published");
        r.setStatus("published");
        r.setPublishedAt(OffsetDateTime.now());
        return toDto(repo.save(r));
    }

    @Transactional
    public ReportDto uploadDocument(Long id, MultipartFile file) throws IOException {
        Report r = repo.findById(id).orElseThrow(() -> NotFoundException.of("report", id));
        orgScope.assertOrgAccess(currentAuth(), r.getOrganizationId());
        String ext = file.getOriginalFilename() != null && file.getOriginalFilename().contains(".")
            ? file.getOriginalFilename().substring(file.getOriginalFilename().lastIndexOf('.'))
            : ".docx";
        String objectKey = r.getOrganizationId() + "/reports/" + System.currentTimeMillis() + "_" + id + ext;
        storage.put(exportsBucket, objectKey,
            file.getContentType() != null ? file.getContentType() : "application/octet-stream",
            file.getBytes());
        r.setReportBucket(exportsBucket);
        r.setReportObjectKey(objectKey);
        r.setStatus("draft");
        r.setCompletedAt(OffsetDateTime.now());
        return toDto(repo.save(r));
    }

    public byte[] download(Long id) {
        Report r = repo.findById(id).orElseThrow(() -> NotFoundException.of("report", id));
        var auth = currentAuth();
        orgScope.assertOrgAccess(auth, r.getOrganizationId());
        if (isClientUser(auth) && !"published".equals(r.getStatus())) {
            throw NotFoundException.of("report", id);
        }
        if (r.getReportObjectKey() == null) throw new IllegalStateException("Report has no downloadable file");
        String bucket = r.getReportBucket() != null ? r.getReportBucket() : exportsBucket;
        return storage.get(bucket, r.getReportObjectKey());
    }

    @Transactional
    public void delete(Long id) {
        if (!repo.existsById(id)) throw NotFoundException.of("report", id);
        repo.deleteById(id);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ReportDto toDto(Report r) {
        int findingCount = findingLinkRepo.findByReportId(r.getId()).size();
        List<ReportField> fields = fieldRepo.findByReportId(r.getId());
        return ReportDto.from(r, findingCount, fields);
    }

    private static Long resolveUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || "anonymousUser".equals(auth.getName())) return null;
        try { return Long.parseLong(auth.getName()); } catch (NumberFormatException e) { return null; }
    }

    private static String blank(String s) { return (s == null || s.isBlank()) ? null : s; }
}
