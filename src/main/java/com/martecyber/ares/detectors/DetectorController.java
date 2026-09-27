package com.martecyber.ares.detectors;

import com.martecyber.ares.common.PagedResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/detector-tools")
public class DetectorController {

    private final DetectorService service;

    public DetectorController(DetectorService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public PagedResponse<DetectorTool> listTools(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size
    ) {
        return PagedResponse.of(service.listTools(page, size), t -> t);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public DetectorTool getTool(@PathVariable Long id) {
        return service.getTool(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<DetectorTool> createTool(@RequestBody CreateToolRequest req) {
        DetectorTool created = service.createTool(req.name(), req.description());
        return ResponseEntity.created(URI.create("/api/v1/detector-tools/" + created.getId())).body(created);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Void> deleteTool(@PathVariable Long id) {
        service.deleteTool(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{toolId}/plugins")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public PagedResponse<DetectorPlugin> listPlugins(
        @PathVariable Long toolId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size
    ) {
        return PagedResponse.of(service.listPlugins(toolId, page, size), p -> p);
    }

    @PostMapping("/{toolId}/plugins")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<DetectorPlugin> createPlugin(@PathVariable Long toolId, @RequestBody CreatePluginRequest req) {
        DetectorPlugin created = service.createPlugin(toolId, req.code());
        return ResponseEntity.created(URI.create("/api/v1/detector-tools/" + toolId + "/plugins/" + created.getId())).body(created);
    }

    @DeleteMapping("/{toolId}/plugins/{pluginId}")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Void> deletePlugin(@PathVariable Long toolId, @PathVariable Long pluginId) {
        service.deletePlugin(pluginId);
        return ResponseEntity.noContent().build();
    }

    record CreateToolRequest(@NotBlank String name, String description) {}
    record CreatePluginRequest(@NotBlank String code) {}
}
