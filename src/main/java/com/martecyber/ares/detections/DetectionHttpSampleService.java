package com.martecyber.ares.detections;

import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.detections.dto.CreateDetectionHttpSampleRequest;
import com.martecyber.ares.detections.dto.DetectionHttpSampleDto;
import com.martecyber.ares.projects.Project;
import com.martecyber.ares.projects.ProjectRepository;
import com.martecyber.ares.users.OrgScopeService;
import jakarta.transaction.Transactional;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * CRUD for HTTP request/response samples attached to detections.
 * Mirrors {@code com.martecyber.ares.assets.WebEndpointHttpSampleService}.
 */
@Service
public class DetectionHttpSampleService {

    private final DetectionHttpSampleRepository repo;
    private final DetectionRepository detectionRepo;
    private final ProjectRepository projectRepo;
    private final OrgScopeService orgScope;

    public DetectionHttpSampleService(DetectionHttpSampleRepository repo, DetectionRepository detectionRepo,
                                       ProjectRepository projectRepo, OrgScopeService orgScope) {
        this.repo = repo;
        this.detectionRepo = detectionRepo;
        this.projectRepo = projectRepo;
        this.orgScope = orgScope;
    }

    /** True for both client tiers (CLIENT_USER and CLIENT_ADMIN). */
    private static boolean isClientUser(Authentication auth) {
        return auth != null && auth.getAuthorities().stream()
            .anyMatch(a -> "ROLE_CLIENT_USER".equals(a.getAuthority()) || "ROLE_CLIENT_ADMIN".equals(a.getAuthority()));
    }

    public List<DetectionHttpSampleDto> listByDetection(Long detectionId) {
        Detection d = ensureDetection(detectionId);
        var auth = SecurityContextHolder.getContext().getAuthentication();
        orgScope.assertProjectAccess(auth, d.getProjectId());
        if (isClientUser(auth)) {
            Project p = projectRepo.findById(d.getProjectId())
                .orElseThrow(() -> NotFoundException.of("project", d.getProjectId()));
            if (!p.isClientsCanViewDetections()) {
                throw new AccessDeniedException("Detections are not visible to clients for this project");
            }
        }
        return repo.findByDetectionId(detectionId).stream().map(DetectionHttpSampleDto::from).toList();
    }

    // REQUIRES_NEW: callers like ImportService create samples as a best-effort side effect of a
    // much bigger @Transactional import and deliberately swallow failures here so one bad sample
    // doesn't block the rest of the import — but REQUIRED would still silently mark the *caller's*
    // transaction rollback-only on any exception (even a caught one), so the whole import would
    // fail to commit anyway. Running in its own transaction means a failure here rolls back only
    // this row, exactly like AssetImportHelper.resolveOrCreate does for the same reason.
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public DetectionHttpSampleDto create(Long detectionId, CreateDetectionHttpSampleRequest req) {
        ensureDetection(detectionId);
        DetectionHttpSample s = new DetectionHttpSample();
        s.setDetectionId(detectionId);
        s.setLabel(req.label());
        s.setRequestContent(req.requestContent());
        s.setResponseContent(req.responseContent());
        s.setNotes(req.notes());
        OffsetDateTime now = OffsetDateTime.now();
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        return DetectionHttpSampleDto.from(repo.save(s));
    }

    @Transactional
    public DetectionHttpSampleDto update(Long detectionId, Long sampleId, CreateDetectionHttpSampleRequest req) {
        DetectionHttpSample s = repo.findById(sampleId)
            .filter(x -> x.getDetectionId().equals(detectionId))
            .orElseThrow(() -> NotFoundException.of("detection_http_sample", sampleId));
        if (req.label() != null)           s.setLabel(req.label());
        if (req.requestContent() != null)  s.setRequestContent(req.requestContent());
        if (req.responseContent() != null) s.setResponseContent(req.responseContent());
        if (req.notes() != null)            s.setNotes(req.notes());
        s.setUpdatedAt(OffsetDateTime.now());
        return DetectionHttpSampleDto.from(repo.save(s));
    }

    @Transactional
    public void delete(Long detectionId, Long sampleId) {
        DetectionHttpSample s = repo.findById(sampleId)
            .filter(x -> x.getDetectionId().equals(detectionId))
            .orElseThrow(() -> NotFoundException.of("detection_http_sample", sampleId));
        repo.delete(s);
    }

    private Detection ensureDetection(Long detectionId) {
        return detectionRepo.findById(detectionId)
            .orElseThrow(() -> NotFoundException.of("detection", detectionId));
    }
}
