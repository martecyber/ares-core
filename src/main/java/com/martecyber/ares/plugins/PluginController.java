package com.martecyber.ares.plugins;

import com.martecyber.ares.plugins.dto.PluginBrowseEntryDto;
import com.martecyber.ares.plugins.dto.PluginBrowseVersionDto;
import com.martecyber.ares.plugins.dto.PluginDto;
import com.martecyber.ares.plugins.dto.PluginRepositorySourceDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/plugins")
@PreAuthorize("hasRole('MSSP_ADMIN')")
public class PluginController {

    private final PluginService service;
    private final PluginRepositorySourceService repoSourceService;
    private final PluginBrowseService browseService;

    public PluginController(PluginService service, PluginRepositorySourceService repoSourceService,
                             PluginBrowseService browseService) {
        this.service = service;
        this.repoSourceService = repoSourceService;
        this.browseService = browseService;
    }

    @GetMapping
    public List<PluginDto> list() {
        return service.list();
    }

    /** {@code replace=false} (the default): if a plugin with the same manifest id is already
     *  installed, nothing is touched and the response is 409 with a {@link
     *  InstallResult.VersionConflict} body instead of the usual 201 {@link PluginDto} — the admin
     *  UI turns that into an info/confirm modal and, on confirm, re-submits this same call with
     *  {@code replace=true} (the exact same file — the browser still has it in memory from the
     *  original file picker selection, no second upload needed). */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file,
                                     @RequestParam(defaultValue = "false") boolean replace,
                                     Authentication auth) {
        return toResponse(service.installFromUpload(file, currentUserId(auth), replace));
    }

    @PatchMapping("/{id}/enabled")
    public PluginDto setEnabled(@PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        return service.setEnabled(id, Boolean.TRUE.equals(body.get("enabled")));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void uninstall(@PathVariable Long id) {
        service.uninstall(id);
    }

    // ── Repositories ──────────────────────────────────────────────────────────

    @GetMapping("/repositories")
    public List<PluginRepositorySourceDto> repositories() {
        return repoSourceService.list();
    }

    public record AddRepositoryRequest(String name, String baseUrl) {}

    @PostMapping("/repositories")
    @ResponseStatus(HttpStatus.CREATED)
    public PluginRepositorySourceDto addRepository(@RequestBody AddRepositoryRequest req, Authentication auth) {
        return repoSourceService.add(req.name(), req.baseUrl(), currentUserId(auth));
    }

    @PatchMapping("/repositories/{id}/enabled")
    public PluginRepositorySourceDto setRepositoryEnabled(@PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        return repoSourceService.setEnabled(id, Boolean.TRUE.equals(body.get("enabled")));
    }

    @DeleteMapping("/repositories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRepository(@PathVariable Long id) {
        repoSourceService.delete(id);
    }

    // ── Browse ────────────────────────────────────────────────────────────────

    @GetMapping("/browse")
    public List<PluginBrowseEntryDto> browse() {
        return browseService.browse();
    }

    @GetMapping("/browse/{pluginId}/versions")
    public List<PluginBrowseVersionDto> browseVersions(@PathVariable String pluginId, @RequestParam Long repositoryId) {
        return browseService.browseVersions(repositoryId, pluginId);
    }

    public record InstallFromRepositoryRequest(Long repositoryId, String pluginId, String version, boolean replace) {}

    /** Same {@code replace} contract as {@link #upload} — see its own doc comment. */
    @PostMapping("/install")
    public ResponseEntity<?> installFromRepository(@RequestBody InstallFromRepositoryRequest req, Authentication auth) {
        return toResponse(service.installFromRepository(req.repositoryId(), req.pluginId(), req.version(),
            currentUserId(auth), req.replace()));
    }

    private static ResponseEntity<?> toResponse(InstallResult result) {
        return switch (result) {
            case InstallResult.Installed r -> ResponseEntity.status(HttpStatus.CREATED).body(r.plugin());
            case InstallResult.VersionConflict c -> ResponseEntity.status(HttpStatus.CONFLICT).body(c);
        };
    }

    private static Long currentUserId(Authentication auth) {
        try {
            return Long.parseLong(auth.getName());
        } catch (Exception e) {
            return null;
        }
    }
}
