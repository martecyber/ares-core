package com.martecyber.ares.projects;

import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.plugins.Plugin;
import com.martecyber.ares.plugins.PluginRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/project-types")
public class ProjectTypeController {

    private final ProjectTypeRepository repo;
    private final PluginRepository pluginRepo;

    public ProjectTypeController(ProjectTypeRepository repo, PluginRepository pluginRepo) {
        this.repo = repo;
        this.pluginRepo = pluginRepo;
    }

    /** Lets the frontend render "which plugin contributed this type" generically (icon + real
     *  display name, e.g. the Project Types catalog's per-plugin section) instead of hardcoding
     *  plugin identities — same join pattern as {@code IntegrationController#types}. */
    @GetMapping
    public List<ProjectTypeDto> list() {
        List<ProjectType> all = repo.findAll();
        Map<Long, String> nameById = all.stream()
            .collect(Collectors.toMap(ProjectType::getId, ProjectType::getName));
        return all.stream()
            .map(t -> toDto(t, t.getSupertypeId() != null ? nameById.get(t.getSupertypeId()) : null))
            .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectTypeDto create(@Valid @RequestBody ProjectTypeRequest req) {
        ProjectType parent = repo.findById(req.supertypeId())
            .orElseThrow(() -> NotFoundException.of("project_type", req.supertypeId()));
        if (!parent.isSystem()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Parent must be a root type");
        }
        if (parent.isDisabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Cannot create subtypes under a disabled root type");
        }
        ProjectType t = new ProjectType();
        t.setName(req.name().trim());
        t.setCode(req.code().trim().toUpperCase());
        t.setDescription(req.description() != null ? req.description().trim() : null);
        t.setSupertypeId(req.supertypeId());
        return toDto(repo.save(t), parent.getName());
    }

    @PatchMapping("/{id}/disabled")
    public ProjectTypeDto setDisabled(@PathVariable Long id, @RequestBody java.util.Map<String, Boolean> body) {
        ProjectType t = repo.findById(id).orElseThrow(() -> NotFoundException.of("project_type", id));
        boolean disable = Boolean.TRUE.equals(body.get("disabled"));
        if (!disable && "MONITOR".equals(t.getCode())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Monitoring type cannot be re-enabled");
        }
        if (t.getRequiredPluginId() != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "'" + t.getCode() + "' is managed by the '" + t.getRequiredPluginId()
                    + "' plugin — install/enable or disable/uninstall that plugin instead of toggling this directly");
        }
        t.setDisabled(disable);
        String supertypeName = t.getSupertypeId() != null
            ? repo.findById(t.getSupertypeId()).map(ProjectType::getName).orElse(null) : null;
        return toDto(repo.save(t), supertypeName);
    }

    @PutMapping("/{id}")
    public ProjectTypeDto update(@PathVariable Long id, @Valid @RequestBody ProjectTypeRequest req) {
        ProjectType t = repo.findById(id).orElseThrow(() -> NotFoundException.of("project_type", id));
        if (t.isSystem()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "System types cannot be modified");
        }
        ProjectType parent = repo.findById(req.supertypeId())
            .orElseThrow(() -> NotFoundException.of("project_type", req.supertypeId()));
        if (!parent.isSystem()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Parent must be a root type (Assessment or Monitoring)");
        }
        t.setName(req.name().trim());
        t.setCode(req.code().trim().toUpperCase());
        t.setDescription(req.description() != null ? req.description().trim() : null);
        t.setSupertypeId(req.supertypeId());
        return toDto(repo.save(t), parent.getName());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        ProjectType t = repo.findById(id).orElseThrow(() -> NotFoundException.of("project_type", id));
        if (t.isSystem()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "System types cannot be deleted");
        }
        repo.deleteById(id);
    }

    private ProjectTypeDto toDto(ProjectType t, String supertypeName) {
        Plugin plugin = t.getRequiredPluginId() != null
            ? pluginRepo.findByPluginId(t.getRequiredPluginId()).orElse(null) : null;
        return new ProjectTypeDto(t.getId(), t.getName(), t.getCode(), t.getDescription(),
            t.getSupertypeId(), supertypeName, t.isSystem(), t.isDisabled(), t.getRequiredPluginId(),
            plugin != null ? plugin.getDisplayName() : null,
            plugin != null ? plugin.getIcon() : null,
            plugin != null ? plugin.getIconLight() : null);
    }

    public record ProjectTypeDto(
        Long id, String name, String code, String description,
        Long supertypeId, String supertypeName,
        boolean system, boolean disabled, String requiredPluginId,
        String pluginDisplayName, String pluginIcon, String pluginIconLight
    ) {}

    public record ProjectTypeRequest(
        @NotBlank String name,
        @NotBlank String code,
        String description,
        @NotNull Long supertypeId
    ) {}
}
