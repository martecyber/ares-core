package com.martecyber.ares.editorimages;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/editor-images")
public class EditorImageController {

    private final EditorImageService svc;

    public EditorImageController(EditorImageService svc) { this.svc = svc; }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public Map<String, Object> upload(
        @RequestPart("file") MultipartFile file,
        @RequestParam(required = false) Long organizationId,
        @RequestParam(required = false) Long projectId
    ) throws IOException {
        var result = svc.upload(file, organizationId, projectId);
        // Absolute URL (respecting X-Forwarded-* — see application.yml's
        // server.forward-headers-strategy: framework) rather than a relative path: this
        // gets stored in the field's Markdown. The frontend resolves it via an authenticated
        // fetch + blob URL (not a raw <img src> — see EditorImageService's class doc), and
        // ReportGenerationService resolves it in-process, so nothing needs this to be
        // browser-relative-path-free anymore, but an absolute URL is still the simplest form to
        // embed directly in Markdown regardless of caller.
        String url = ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/api/v1/editor-images/{token}")
            .buildAndExpand(result.token())
            .toUriString();
        return Map.of("id", result.id(), "url", url);
    }

    /** Requires authentication (any logged-in user — {@link EditorImageService#get} enforces the
     *  image's own org/project/platform-staff scope). Identified by an unguessable token, not the
     *  sequential id — see V206's migration comment for the full history of why this changed. */
    @GetMapping("/{token}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> get(@PathVariable UUID token) {
        var img = svc.get(token);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(img.contentType()))
            .header("Cache-Control", "private, max-age=31536000, immutable")
            .body(img.bytes());
    }
}
