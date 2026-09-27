package com.martecyber.ares.priorization.ssvc;

import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.martecyber.ares.priorization.ssvc.dto.SsvcMethodologyDto;
import com.martecyber.ares.priorization.ssvc.dto.SsvcMethodologyExportDto;
import com.martecyber.ares.priorization.ssvc.dto.SsvcRoleDetailDto;
import com.martecyber.ares.priorization.ssvc.dto.SsvcRequests.*;

import java.net.URI;
import java.util.List;

@RestController
public class SsvcMethodologyController {

    private final SsvcMethodologyService service;

    public SsvcMethodologyController(SsvcMethodologyService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/ssvc-methodologies")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<SsvcMethodologyDto> list() {
        return service.list();
    }

    @GetMapping("/api/v1/ssvc-methodologies/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public SsvcMethodologyDto getDetail(@PathVariable Long id) {
        return service.getDetail(id);
    }

    @PostMapping("/api/v1/ssvc-methodologies")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR') and hasAuthority('SSVC_WRITE')")
    public ResponseEntity<SsvcMethodologyDto> createMethodology(@Valid @RequestBody CreateMethodologyRequest req) {
        SsvcMethodologyDto created = service.createMethodology(req);
        return ResponseEntity.created(URI.create("/api/v1/ssvc-methodologies/" + created.id())).body(created);
    }

    @PatchMapping("/api/v1/ssvc-methodologies/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR') and hasAuthority('SSVC_WRITE')")
    public SsvcMethodologyDto updateMethodology(@PathVariable Long id, @RequestBody UpdateMethodologyRequest req) {
        return service.updateMethodology(id, req);
    }

    @DeleteMapping("/api/v1/ssvc-methodologies/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR') and hasAuthority('SSVC_WRITE')")
    public ResponseEntity<Void> deleteMethodology(@PathVariable Long id) {
        service.deleteMethodology(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/ssvc-methodologies/{id}/roles")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR') and hasAuthority('SSVC_WRITE')")
    public ResponseEntity<SsvcMethodologyDto.SsvcRoleSummaryDto> createRole(@PathVariable Long id, @Valid @RequestBody CreateRoleRequest req) {
        var created = service.createRole(id, req);
        return ResponseEntity.created(URI.create("/api/v1/ssvc-roles/" + created.id())).body(created);
    }

    @GetMapping("/api/v1/ssvc-roles/{roleId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public SsvcRoleDetailDto getRoleDetail(@PathVariable Long roleId) {
        return service.getRoleDetail(roleId);
    }

    @GetMapping("/api/v1/ssvc-tree-nodes/{nodeId}/role")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public java.util.Map<String, Long> getRoleForNode(@PathVariable Long nodeId) {
        return java.util.Map.of("roleId", service.getRoleIdForNode(nodeId));
    }

    @PatchMapping("/api/v1/ssvc-roles/{roleId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR') and hasAuthority('SSVC_WRITE')")
    public SsvcMethodologyDto.SsvcRoleSummaryDto updateRole(@PathVariable Long roleId, @RequestBody UpdateRoleRequest req) {
        return service.updateRole(roleId, req);
    }

    @DeleteMapping("/api/v1/ssvc-roles/{roleId}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR') and hasAuthority('SSVC_WRITE')")
    public ResponseEntity<Void> deleteRole(@PathVariable Long roleId) {
        service.deleteRole(roleId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/v1/ssvc-roles/{roleId}/tree")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR') and hasAuthority('SSVC_WRITE')")
    public SsvcRoleDetailDto replaceTree(@PathVariable Long roleId, @Valid @RequestBody ReplaceTreeRequest req) {
        return service.replaceTree(roleId, req);
    }

    // ── Export — read-only, so no SSVC_WRITE gate (works for system methodologies too) ──

    @GetMapping("/api/v1/ssvc-methodologies/{id}/export")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<SsvcMethodologyExportDto> exportOne(@PathVariable Long id) {
        SsvcMethodologyExportDto data = service.exportOne(id);
        String filename = "ssvc-methodology-" + id + ".json";
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(MediaType.APPLICATION_JSON)
            .body(data);
    }

    @PostMapping("/api/v1/ssvc-methodologies/export-zip")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<byte[]> exportZip(@RequestBody List<Long> ids) {
        byte[] zip = service.exportZip(ids);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ssvc-methodologies.zip\"")
            .contentType(MediaType.parseMediaType("application/zip"))
            .body(zip);
    }

    /** Accepts one or more .json files (one methodology each) or .zip files (a batch, as
     *  produced by export-zip). Creates a new custom methodology (with all its roles and
     *  trees) per entry — imported methodologies are never system methodologies. */
    @PostMapping(value = "/api/v1/ssvc-methodologies/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR') and hasAuthority('SSVC_WRITE')")
    public SsvcMethodologyImportResult importFiles(@RequestParam("files") List<MultipartFile> files) {
        return service.importFiles(files);
    }
}
