package com.martecyber.ares.agents.tasks.templates;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.agents.tasks.AgentTask;
import com.martecyber.ares.agents.tasks.AgentTaskRepository;
import com.martecyber.ares.agents.tasks.AgentToolSpecRegistry;
import com.martecyber.ares.common.NotFoundException;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
public class AgentTaskTemplateService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final AgentTaskTemplateRepository repo;
    private final AgentTaskRepository taskRepo;
    private final AgentToolSpecRegistry agentToolSpecRegistry;

    public AgentTaskTemplateService(AgentTaskTemplateRepository repo,
                                    AgentTaskRepository taskRepo,
                                    AgentToolSpecRegistry agentToolSpecRegistry) {
        this.repo = repo;
        this.taskRepo = taskRepo;
        this.agentToolSpecRegistry = agentToolSpecRegistry;
    }

    public Page<AgentTaskTemplateDto> list(int page, int size) {
        var p = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200));
        return repo.findAllByOrderByCreatedAtDesc(p).map(AgentTaskTemplateDto::from);
    }

    public AgentTaskTemplateDto get(Long id) {
        return AgentTaskTemplateDto.from(repo.findById(id)
            .orElseThrow(() -> NotFoundException.of("agent_task_template", id)));
    }

    @Transactional
    public AgentTaskTemplateDto create(CreateOrUpdateRequest req) {
        return create(req, false);
    }

    /** @param overwrite when {@code false} and a template already has this exact name, throws
     *      {@link com.martecyber.ares.common.DuplicateNameException} instead of creating a
     *      second one — same call {@link com.martecyber.ares.workflows.templates.WorkflowTemplateService#create(CreateOrUpdateRequest, boolean)}
     *      makes. When {@code true}, delegates to {@link #update} on that existing row instead. */
    @Transactional
    public AgentTaskTemplateDto create(CreateOrUpdateRequest req, boolean overwrite) {
        validate(req);
        var existing = repo.findByNameIgnoreCase(req.name().trim());
        if (existing.isPresent()) {
            if (!overwrite) {
                throw new com.martecyber.ares.common.DuplicateNameException(
                    "A task template named \"" + req.name().trim() + "\" already exists");
            }
            return update(existing.get().getId(), req);
        }
        AgentTaskTemplate t = new AgentTaskTemplate();
        OffsetDateTime now = OffsetDateTime.now();
        applyInto(t, req);
        t.setCreatorId(currentUserId());
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        return AgentTaskTemplateDto.from(repo.save(t));
    }

    @Transactional
    public AgentTaskTemplateDto update(Long id, CreateOrUpdateRequest req) {
        AgentTaskTemplate t = repo.findById(id)
            .orElseThrow(() -> NotFoundException.of("agent_task_template", id));
        validate(req);
        applyInto(t, req);
        t.setUpdatedAt(OffsetDateTime.now());
        return AgentTaskTemplateDto.from(repo.save(t));
    }

    @Transactional
    public void delete(Long id) {
        if (!repo.existsById(id)) throw NotFoundException.of("agent_task_template", id);
        repo.deleteById(id);
    }

    // ── Export / import ──────────────────────────────────────────────────────────
    // Export reuses CreateOrUpdateRequest's own shape as the file format, so a
    // round-tripped export→import needs no separate DTO or mapping on the way back in.

    public CreateOrUpdateRequest exportOne(Long id) {
        return toExportRequest(repo.findById(id)
            .orElseThrow(() -> NotFoundException.of("agent_task_template", id)));
    }

    private CreateOrUpdateRequest toExportRequest(AgentTaskTemplate t) {
        return new CreateOrUpdateRequest(
            t.getName(), t.getDescription(), t.getTool(), t.getFormat(),
            parseArgs(t.getArgs()), t.getNacProfile(), t.getTimeoutMinutes()
        );
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
    public AgentTaskTemplateImportResult importFiles(java.util.List<org.springframework.web.multipart.MultipartFile> files) {
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
        return new AgentTaskTemplateImportResult(imported, errors);
    }

    /**
     * Snapshot a live task into a new template. Strips runtime/project-specific
     * fields (poolId, projectId, priority, scheduledFor); the operator can override
     * the name/description before save via the request body.
     */
    @Transactional
    public AgentTaskTemplateDto createFromTask(Long taskId, FromTaskRequest req) {
        return createFromTask(taskId, req, false);
    }

    @Transactional
    public AgentTaskTemplateDto createFromTask(Long taskId, FromTaskRequest req, boolean overwrite) {
        AgentTask task = taskRepo.findById(taskId)
            .orElseThrow(() -> NotFoundException.of("agent_task", taskId));
        String name = (req != null && req.name() != null && !req.name().isBlank())
            ? req.name().trim()
            : (task.getName() != null && !task.getName().isBlank() ? task.getName() : task.getTool() + " template");
        CreateOrUpdateRequest body = new CreateOrUpdateRequest(
            name,
            req != null ? req.description() : null,
            task.getTool(),
            task.getFormat(),
            parseArgs(task.getArgs()),
            task.getNacProfile(),
            task.getTimeoutMinutes()
        );
        return create(body, overwrite);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void validate(CreateOrUpdateRequest req) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        if (req.tool() == null || req.tool().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tool is required");
        }
        // Verify the tool exists; the registry throws BAD_REQUEST itself for unknowns.
        agentToolSpecRegistry.describe(req.tool());
        // Permissive args check — templates may store `targetsFrom` (selector) or
        // `targets` (manual), neither/either, plus any optional tool arg. We do NOT
        // require `targets` because templates are project-agnostic; resolution happens
        // when the template is applied to a real task. validateArgKeys mirrors exactly
        // that permissiveness (key presence only, no required-ness) — see its own doc.
        if (req.args() != null) {
            agentToolSpecRegistry.validateArgKeys(req.tool(), req.args().keySet());
        }
    }

    private void applyInto(AgentTaskTemplate t, CreateOrUpdateRequest req) {
        t.setName(req.name().trim());
        t.setDescription(req.description() != null && !req.description().isBlank() ? req.description() : null);
        t.setTool(req.tool());
        t.setFormat(req.format() == null || req.format().isBlank() ? "default" : req.format());
        Map<String, Object> args = req.args() != null ? req.args() : Map.of();
        try { t.setArgs(MAPPER.writeValueAsString(args)); }
        catch (Exception e) { t.setArgs("{}"); }
        t.setNacProfile(req.nacProfile() != null && !req.nacProfile().isBlank() ? req.nacProfile() : null);
        t.setTimeoutMinutes(req.timeoutMinutes());
    }

    private static Map<String, Object> parseArgs(String json) {
        if (json == null || json.isBlank()) return new HashMap<>();
        try { return MAPPER.readValue(json, MAP_TYPE); }
        catch (Exception e) { return new HashMap<>(); }
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
        String tool,
        String format,
        Map<String, Object> args,
        String nacProfile,
        Integer timeoutMinutes
    ) {}

    public record FromTaskRequest(
        /** Optional override. Falls back to the task's name, then the tool name. */
        String name,
        String description
    ) {}
}
