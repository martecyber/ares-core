package com.martecyber.ares.workflows.templates;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.workflows.Workflow;
import com.martecyber.ares.workflows.WorkflowGraphValidator;
import com.martecyber.ares.workflows.WorkflowRepository;
import com.martecyber.ares.workflows.WorkflowScope;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;

@Service
public class WorkflowTemplateService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WorkflowTemplateRepository repo;
    private final WorkflowRepository workflowRepo;
    private final WorkflowGraphValidator graphValidator;

    public WorkflowTemplateService(WorkflowTemplateRepository repo, WorkflowRepository workflowRepo,
                                    WorkflowGraphValidator graphValidator) {
        this.repo = repo;
        this.workflowRepo = workflowRepo;
        this.graphValidator = graphValidator;
    }

    public Page<WorkflowTemplateDto> list(int page, int size) {
        var p = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200));
        return repo.findAllByOrderByCreatedAtDesc(p).map(WorkflowTemplateDto::from);
    }

    public WorkflowTemplateDto get(Long id) {
        return WorkflowTemplateDto.from(repo.findById(id)
            .orElseThrow(() -> NotFoundException.of("workflow_template", id)));
    }

    @Transactional
    public WorkflowTemplateDto create(CreateOrUpdateRequest req) {
        return create(req, false);
    }

    /** @param overwrite when {@code false} and a template already has this exact name, throws
     *      {@link com.martecyber.ares.common.DuplicateNameException} instead of creating a
     *      second one — same call {@link com.martecyber.ares.findings.templates.FindingTemplateService#createFromFinding}
     *      makes. When {@code true}, delegates to {@link #update} on that existing row instead. */
    @Transactional
    public WorkflowTemplateDto create(CreateOrUpdateRequest req, boolean overwrite) {
        validate(req);
        var existing = repo.findByNameIgnoreCase(req.name().trim());
        if (existing.isPresent()) {
            if (!overwrite) {
                throw new com.martecyber.ares.common.DuplicateNameException(
                    "A workflow template named \"" + req.name().trim() + "\" already exists");
            }
            return update(existing.get().getId(), req);
        }
        WorkflowTemplate t = new WorkflowTemplate();
        OffsetDateTime now = OffsetDateTime.now();
        applyInto(t, req);
        t.setCreatorId(currentUserId());
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        return WorkflowTemplateDto.from(repo.save(t));
    }

    @Transactional
    public WorkflowTemplateDto update(Long id, CreateOrUpdateRequest req) {
        WorkflowTemplate t = repo.findById(id)
            .orElseThrow(() -> NotFoundException.of("workflow_template", id));
        validate(req);
        applyInto(t, req);
        t.setUpdatedAt(OffsetDateTime.now());
        return WorkflowTemplateDto.from(repo.save(t));
    }

    @Transactional
    public void delete(Long id) {
        if (!repo.existsById(id)) throw NotFoundException.of("workflow_template", id);
        repo.deleteById(id);
    }

    // ── Export / import ──────────────────────────────────────────────────────────
    // Export reuses CreateOrUpdateRequest's own shape as the file format, so a
    // round-tripped export→import needs no separate DTO or mapping on the way back in.

    public CreateOrUpdateRequest exportOne(Long id) {
        return toExportRequest(repo.findById(id)
            .orElseThrow(() -> NotFoundException.of("workflow_template", id)));
    }

    private CreateOrUpdateRequest toExportRequest(WorkflowTemplate t) {
        return new CreateOrUpdateRequest(t.getName(), t.getDescription(), t.getScopeKind(), t.getGraphDefinition());
    }

    /** A .json file per template, zipped together — used for the "export selected" bulk
     *  action. Zip entry names are best-effort human-readable (sanitized name + id) and
     *  otherwise carry no meaning; import re-parses each entry purely by content. */
    public byte[] exportZip(java.util.List<Long> ids) {
        try (var baos = new java.io.ByteArrayOutputStream();
             var zos = new java.util.zip.ZipOutputStream(baos)) {
            for (Long id : ids) {
                CreateOrUpdateRequest data = exportOne(id);
                byte[] json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(data);
                String safeName = data.name().replaceAll("[^a-zA-Z0-9-]+", "_");
                zos.putNextEntry(new java.util.zip.ZipEntry(safeName + "-" + id + ".json"));
                zos.write(json);
                zos.closeEntry();
            }
            zos.finish();
            return baos.toByteArray();
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to build export zip", e);
        }
    }

    /** Accepts any mix of .json files (one template each) and .zip files (a batch of
     *  .json entries, as produced by exportZip) in a single upload. Best-effort: one bad
     *  file or entry is reported in the result rather than aborting the whole import. */
    @Transactional
    public WorkflowTemplateImportResult importFiles(java.util.List<org.springframework.web.multipart.MultipartFile> files) {
        int imported = 0;
        java.util.List<String> errors = new java.util.ArrayList<>();
        for (var file : files) {
            String name = file.getOriginalFilename() != null ? file.getOriginalFilename() : "file";
            try {
                if (name.toLowerCase().endsWith(".zip")) {
                    try (var zis = new java.util.zip.ZipInputStream(file.getInputStream())) {
                        java.util.zip.ZipEntry entry;
                        while ((entry = zis.getNextEntry()) != null) {
                            if (entry.isDirectory() || !entry.getName().toLowerCase().endsWith(".json")) continue;
                            byte[] bytes = zis.readAllBytes();
                            try {
                                create(MAPPER.readValue(bytes, CreateOrUpdateRequest.class));
                                imported++;
                            } catch (Exception e) {
                                errors.add(name + "/" + entry.getName() + ": " + e.getMessage());
                            }
                        }
                    }
                } else {
                    create(MAPPER.readValue(file.getBytes(), CreateOrUpdateRequest.class));
                    imported++;
                }
            } catch (Exception e) {
                errors.add(name + ": " + e.getMessage());
            }
        }
        return new WorkflowTemplateImportResult(imported, errors);
    }

    /**
     * Snapshot a live workflow's graph into a new template. Runs it through
     * {@link WorkflowTemplateGraphStripper} first so scope-bound resource ids (pool,
     * integration) from the source org/project don't silently carry over into whatever
     * org/project later instantiates the template.
     */
    @Transactional
    public WorkflowTemplateDto createFromWorkflow(Long workflowId, FromWorkflowRequest req) {
        return createFromWorkflow(workflowId, req, false);
    }

    @Transactional
    public WorkflowTemplateDto createFromWorkflow(Long workflowId, FromWorkflowRequest req, boolean overwrite) {
        Workflow wf = workflowRepo.findById(workflowId)
            .orElseThrow(() -> NotFoundException.of("workflow", workflowId));
        String name = (req != null && req.name() != null && !req.name().isBlank())
            ? req.name().trim()
            : (wf.getName() != null && !wf.getName().isBlank() ? wf.getName() : "Workflow template");
        String description = req != null ? req.description() : null;
        String strippedGraph = WorkflowTemplateGraphStripper.strip(wf.getGraphDefinition());
        // Defaults to the source workflow's own scope — a template snapshotted from a
        // project-scoped workflow is, by construction, built out of nodes only valid at project
        // scope, so that's what it should declare itself as unless the caller overrides it.
        String scopeKind = (req != null && req.scopeKind() != null && !req.scopeKind().isBlank())
            ? req.scopeKind() : wf.getScopeKind();
        return create(new CreateOrUpdateRequest(name, description, scopeKind, strippedGraph), overwrite);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static final java.util.Set<String> VALID_SCOPE_KINDS =
        java.util.Set.of(WorkflowScope.PLATFORM, WorkflowScope.ORGANIZATION, WorkflowScope.PROJECT);

    private void validate(CreateOrUpdateRequest req) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        if (req.graphDefinition() == null || req.graphDefinition().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "graphDefinition is required");
        }
        try {
            MAPPER.readTree(req.graphDefinition());
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "graphDefinition is not valid JSON");
        }
        // Missing only for a pre-scope-kind export file being re-imported (see resolveScopeKind) —
        // every other caller (the template editor, a fresh export) always sends an explicit value.
        String scopeKind = resolveScopeKind(req.scopeKind());
        if (!VALID_SCOPE_KINDS.contains(scopeKind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "scopeKind must be one of " + VALID_SCOPE_KINDS + ", got '" + req.scopeKind() + "'");
        }
        // Same structural + scope-gating rules a real Workflow's graph gets, run in the same
        // "draft-complete" mode a freshly-created/instantiated workflow is validated in
        // (requireComplete=false) — a template is never itself executable, so the scope-bound
        // resource ids WorkflowTemplateGraphStripper strips are never going to be present.
        graphValidator.validate(scopeKind, req.graphDefinition(), false);
    }

    /** Pre-scope-kind export files (created before this field existed) re-imported through {@link
     *  #importFiles} deserialize {@code scopeKind} as null — treated as {@code platform}, mirroring
     *  the V166 migration's own backfill default for pre-existing rows, rather than hard-failing
     *  an otherwise-valid legacy import. */
    private String resolveScopeKind(String scopeKind) {
        return (scopeKind == null || scopeKind.isBlank()) ? WorkflowScope.PLATFORM : scopeKind;
    }

    private void applyInto(WorkflowTemplate t, CreateOrUpdateRequest req) {
        t.setName(req.name().trim());
        t.setDescription(req.description() != null && !req.description().isBlank() ? req.description() : null);
        t.setScopeKind(resolveScopeKind(req.scopeKind()));
        t.setGraphDefinition(req.graphDefinition());
    }

    private static Long currentUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || "anonymousUser".equals(auth.getName())) return null;
        try { return Long.parseLong(auth.getName()); } catch (NumberFormatException e) { return null; }
    }

    // ── request records ──────────────────────────────────────────────────────

    public record CreateOrUpdateRequest(
        String name,
        String description,
        /** {@code WorkflowScope} value — which level this template is meant to be instantiated
         *  at. Null only accepted from a pre-scope-kind export re-import (see resolveScopeKind). */
        String scopeKind,
        /** Same shape as workflow.graph_definition (nodes+edges), JSON-encoded as a string. */
        String graphDefinition
    ) {}

    public record FromWorkflowRequest(
        /** Optional override. Falls back to the source workflow's name. */
        String name,
        String description,
        /** Optional override. Falls back to the source workflow's own scopeKind. */
        String scopeKind
    ) {}
}
