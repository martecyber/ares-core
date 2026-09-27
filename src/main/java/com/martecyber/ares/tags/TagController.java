package com.martecyber.ares.tags;

import com.martecyber.ares.tags.dto.UpsertTagRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
public class TagController {

    private final TagService service;

    public TagController(TagService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/organizations/{orgId}/tags")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<TagDto> list(@PathVariable Long orgId) {
        return service.list(orgId);
    }

    @PostMapping("/api/v1/organizations/{orgId}/tags")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<TagDto> create(@PathVariable Long orgId, @RequestBody UpsertTagRequest req) {
        TagDto created = service.create(orgId, req);
        return ResponseEntity.created(URI.create("/api/v1/tags/" + created.id())).body(created);
    }

    /** Platform tags — no organization of their own; the only tag tier Exploit/FindingTemplate
     *  (platform-wide catalog entities) can ever receive. Readable by any operator, mutation is
     *  admin-only (see {@link TagService#requirePlatformAdmin}). */
    @GetMapping("/api/v1/tags/platform")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<TagDto> listPlatform() {
        return service.listPlatform();
    }

    @PostMapping("/api/v1/tags/platform")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<TagDto> createPlatform(@RequestBody UpsertTagRequest req) {
        TagDto created = service.createPlatform(req);
        return ResponseEntity.created(URI.create("/api/v1/tags/" + created.id())).body(created);
    }

    @PatchMapping("/api/v1/tags/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public TagDto update(@PathVariable Long id, @RequestBody UpsertTagRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/api/v1/tags/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
