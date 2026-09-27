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

@RestController
@RequestMapping("/api/v1/editor-images")
public class EditorImageController {

    private final EditorImageService svc;

    public EditorImageController(EditorImageService svc) { this.svc = svc; }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public Map<String, Object> upload(@RequestPart("file") MultipartFile file) throws IOException {
        var result = svc.upload(file);
        // Absolute URL (respecting X-Forwarded-* — see application.yml's
        // server.forward-headers-strategy: framework) rather than a relative path: this
        // gets stored in the field's Markdown, and ReportGenerationService.decodeImage
        // fetches it with a plain server-side HTTP GET when rendering a Word document, which
        // can't resolve a browser-relative path.
        String url = ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/api/v1/editor-images/{id}")
            .buildAndExpand(result.id())
            .toUriString();
        return Map.of("id", result.id(), "url", url);
    }

    /** Public — no auth required, so a plain {@code <img src>} works from the editor's
     *  preview and from generated Word documents (see EditorImageService's class doc). */
    @GetMapping("/{id}")
    public ResponseEntity<byte[]> get(@PathVariable Long id) {
        var img = svc.get(id);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(img.contentType()))
            .header("Cache-Control", "public, max-age=31536000, immutable")
            .body(img.bytes());
    }
}
