package com.martecyber.ares.jobs;

import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.jobs.dto.CreateJobRequest;
import com.martecyber.ares.jobs.dto.JobDto;
import com.martecyber.ares.jobs.dto.UpdateJobRequest;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

@Service
public class JobService {

    private final JobRepository repo;

    public JobService(JobRepository repo) { this.repo = repo; }

    private static final Set<String> SORT_FIELDS = Set.of("id", "type", "status", "createdAt", "startedAt", "completedAt");

    public Page<JobDto> list(Long organizationId, Long projectId, List<String> types, List<String> statuses,
                             String sortBy, String sortDir, int page, int size) {
        String col = SORT_FIELDS.contains(sortBy) ? sortBy : "createdAt";
        Sort.Direction dir = "ASC".equalsIgnoreCase(sortDir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200), Sort.by(dir, col));
        List<String> typeList   = (types    == null || types.isEmpty())    ? null : types;
        List<String> statusList = (statuses == null || statuses.isEmpty()) ? null : statuses;
        Specification<Job> spec = Specification
            .where(organizationId == null ? null : (Specification<Job>) (root, q, cb) -> cb.equal(root.get("organizationId"), organizationId))
            .and(projectId == null ? null : (Specification<Job>) (root, q, cb) -> cb.equal(root.get("projectId"), projectId))
            .and(typeList   == null ? null : (root, q, cb) -> root.get("type").in(typeList))
            .and(statusList == null ? null : (root, q, cb) -> root.get("status").in(statusList));
        return repo.findAll(spec, pageable).map(JobDto::from);
    }

    public JobDto get(Long id) {
        return repo.findById(id).map(JobDto::from).orElseThrow(() -> NotFoundException.of("job", id));
    }

    @Transactional
    public JobDto create(CreateJobRequest req) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        Long createdBy = null;
        if (auth != null && auth.getName() != null && !"anonymousUser".equals(auth.getName())) {
            try { createdBy = Long.parseLong(auth.getName()); } catch (NumberFormatException ignored) {}
        }
        Job j = new Job();
        j.setType(req.type());
        j.setOrganizationId(req.organizationId());
        j.setProjectId(req.projectId());
        j.setCreatedBy(createdBy);
        j.setPayload(req.payload());
        OffsetDateTime now = OffsetDateTime.now();
        j.setCreatedAt(now);
        j.setUpdatedAt(now);
        return JobDto.from(repo.save(j));
    }

    @Transactional
    public JobDto update(Long id, UpdateJobRequest req) {
        Job j = repo.findById(id).orElseThrow(() -> NotFoundException.of("job", id));
        if (req.status() != null) {
            // Never overwrite a terminal state — prevents async tasks finishing after cancellation
            // from flipping the status back to completed/failed.
            boolean alreadyTerminal = List.of("completed", "failed", "cancelled").contains(j.getStatus());
            if (!alreadyTerminal) {
                j.setStatus(req.status());
                if ("running".equals(req.status()) && j.getStartedAt() == null) j.setStartedAt(OffsetDateTime.now());
                if (List.of("completed", "failed", "cancelled").contains(req.status())) j.setCompletedAt(OffsetDateTime.now());
            }
        }
        if (req.progress() != null) j.setProgress(req.progress());
        if (req.result() != null) j.setResult(req.result());
        if (req.error() != null) j.setError(req.error());
        j.setUpdatedAt(OffsetDateTime.now());
        return JobDto.from(repo.save(j));
    }

    @Transactional
    public void cancel(Long id) {
        Job j = repo.findById(id).orElseThrow(() -> NotFoundException.of("job", id));
        j.setStatus("cancelled");
        j.setCompletedAt(OffsetDateTime.now());
        j.setUpdatedAt(OffsetDateTime.now());
        repo.save(j);
    }

    public boolean isCancelled(Long jobId) {
        return repo.existsByIdAndStatus(jobId, "cancelled");
    }

    public long countActive() {
        return repo.countByStatusIn(List.of("pending", "running"));
    }

    /** True if a pending/running job of the given type already exists for the project —
     *  used to prevent overlapping syncs (e.g. two Tenable export runs racing each other). */
    public boolean existsActiveByProjectAndType(Long projectId, String type) {
        return repo.existsByProjectIdAndTypeAndStatusIn(projectId, type, List.of("pending", "running"));
    }

}
