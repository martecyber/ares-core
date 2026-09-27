package com.martecyber.ares.kb.wordlists;

import com.martecyber.ares.common.PagedResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/kb/wordlists")
public class KbWordlistController {

    private final KbWordlistService service;

    public KbWordlistController(KbWordlistService service) {
        this.service = service;
    }

    // ── Folders ────────────────────────────────────────────────────────────

    @GetMapping("/folders")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<KbWordlistFolderDto> listFolders() {
        return service.listFolders();
    }

    @PostMapping("/folders")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public KbWordlistFolderDto createFolder(@RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        Long parentId = body.get("parentId") != null
            ? ((Number) body.get("parentId")).longValue()
            : null;
        return service.createFolder(name, parentId);
    }

    @DeleteMapping("/folders/{id}")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Void> deleteFolder(@PathVariable Long id) {
        service.deleteFolder(id);
        return ResponseEntity.noContent().build();
    }

    // ── Wordlists ──────────────────────────────────────────────────────────

    @GetMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','AGENT')")
    public PagedResponse<KbWordlistDto> listWordlists(
        @RequestParam(required = false) Long folderId,
        @RequestParam(required = false, defaultValue = "false") boolean root,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String sortBy,
        @RequestParam(required = false) String sortDir
    ) {
        return service.listWordlists(folderId, root, page, Math.min(size, 200), q, sortBy, sortDir);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public KbWordlistDto upload(
        @RequestParam("file") MultipartFile file,
        @RequestParam(required = false) Long folderId,
        @RequestParam(required = false) String description
    ) throws IOException {
        return service.upload(file, folderId, description);
    }

    @PostMapping(path = "/upload-zip", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public List<KbWordlistDto> uploadZip(
        @RequestParam("file") MultipartFile file,
        @RequestParam(required = false) Long folderId,
        @RequestParam(required = false, defaultValue = "false") boolean importAllTypes
    ) throws IOException {
        return service.uploadZip(file, folderId, importAllTypes);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/bulk")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Void> bulkDelete(@RequestBody List<Long> ids) {
        if (ids != null && !ids.isEmpty()) service.bulkDelete(ids);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/content")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','AGENT')")
    public ResponseEntity<byte[]> getContent(@PathVariable Long id) {
        KbWordlistDto meta = service.getMetadata(id);
        byte[] bytes = service.getContent(id);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + meta.name() + "\"")
            .contentType(MediaType.TEXT_PLAIN)
            .body(bytes);
    }

    @GetMapping("/{id}/metadata")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','AGENT')")
    public KbWordlistDto getMetadata(@PathVariable Long id) {
        return service.getMetadata(id);
    }
}
