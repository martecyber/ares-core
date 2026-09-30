package com.martecyber.ares.editorimages;

import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.storage.StorageService;
import com.martecyber.ares.users.OrgScopeService;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Images uploaded from the Markdown rich-text editor's toolbar/paste/drag-drop — stored via
 * {@link StorageService}, served back by an unguessable {@code token} (see
 * {@link EditorImageController}), gated to whichever organization/project the image was uploaded
 * within (best-effort — the editor is used from platform-wide forms too, where both stay null and
 * access is gated to MSSP staff instead; see V206's own migration comment for the full rationale
 * and why this used to be fully public). The frontend resolves the token URL via an authenticated
 * fetch + blob URL (see {@code utils/markdown.ts}/{@code MarkdownView.vue}) rather than a raw
 * {@code <img src>}; {@code ReportGenerationService} resolves these images in-process instead of
 * over HTTP, since it already runs in an authorized context for the report it's generating.
 */
@Service
public class EditorImageService {

    private static final long MAX_IMAGE_BYTES = 5 * 1024 * 1024; // 5 MB

    private final EditorImageRepository repo;
    private final StorageService storage;
    private final OrgScopeService orgScope;

    @Value("${ares.storage.s3.buckets.editor-images}") private String bucket;

    public EditorImageService(EditorImageRepository repo, StorageService storage, OrgScopeService orgScope) {
        this.repo = repo;
        this.storage = storage;
        this.orgScope = orgScope;
    }

    public record UploadResult(Long id, String token) {}
    public record ImageData(byte[] bytes, String contentType) {}

    @Transactional
    public UploadResult upload(MultipartFile file, Long organizationId, Long projectId) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("File is empty");
        if (file.getSize() > MAX_IMAGE_BYTES) throw new IllegalArgumentException("Image must be under 5 MB");
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("File must be an image");
        }
        if (projectId != null) {
            orgScope.assertProjectAccess(currentAuth(), projectId);
        } else if (organizationId != null) {
            orgScope.assertOrgAccess(currentAuth(), organizationId);
        }

        String objectKey = "editor/" + UUID.randomUUID() + "/" + safeFilename(file.getOriginalFilename());
        storage.put(bucket, objectKey, contentType, file.getBytes());

        EditorImage img = new EditorImage();
        img.setContentType(contentType);
        img.setSizeBytes(file.getSize());
        img.setBucket(bucket);
        img.setObjectKey(objectKey);
        img.setUploadedBy(currentUserId());
        img.setCreatedAt(OffsetDateTime.now());
        img.setToken(UUID.randomUUID());
        img.setOrganizationId(organizationId);
        img.setProjectId(projectId);
        repo.save(img);
        return new UploadResult(img.getId(), img.getToken().toString());
    }

    /** Enforces the image's own org/project scope (platform-wide images require MSSP staff) —
     *  called by the public GET endpoint. */
    public ImageData get(UUID token) {
        EditorImage img = repo.findByToken(token).orElseThrow(() -> NotFoundException.of("editor_image", token));
        assertAccess(img);
        return getInternal(img);
    }

    /** Bypasses the HTTP-layer access check entirely — for a caller (report generation) that's
     *  already running in a context authorized for whatever it's building, not a fresh request
     *  from an arbitrary client. */
    public ImageData getInternal(Long id) {
        EditorImage img = repo.findById(id).orElseThrow(() -> NotFoundException.of("editor_image", id));
        return getInternal(img);
    }

    /** Same as {@link #getInternal(Long)}, resolving by the public token instead — for a caller
     *  that only has the embedded Markdown URL to work with (e.g. ReportGenerationService parsing
     *  an {@code <img src>} attribute), not the DB id. */
    public ImageData getInternalByToken(UUID token) {
        return repo.findByToken(token).map(this::getInternal).orElse(null);
    }

    private ImageData getInternal(EditorImage img) {
        byte[] bytes = storage.get(img.getBucket(), img.getObjectKey());
        return new ImageData(bytes, img.getContentType());
    }

    private void assertAccess(EditorImage img) {
        Authentication auth = currentAuth();
        if (auth == null || auth.getName() == null || "anonymousUser".equals(auth.getName())) {
            throw new AccessDeniedException("Authentication required");
        }
        if (img.getProjectId() != null) {
            orgScope.assertProjectAccess(auth, img.getProjectId());
        } else if (img.getOrganizationId() != null) {
            orgScope.assertOrgAccess(auth, img.getOrganizationId());
        } else if (!isMsspStaff(auth)) {
            throw new AccessDeniedException("Platform-wide images are MSSP-staff only");
        }
    }

    private static boolean isMsspStaff(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a ->
            "ROLE_MSSP_ADMIN".equals(a.getAuthority()) || "ROLE_MSSP_OPERATOR".equals(a.getAuthority()));
    }

    private static Authentication currentAuth() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private static Long currentUserId() {
        Authentication auth = currentAuth();
        if (auth == null || auth.getName() == null || "anonymousUser".equals(auth.getName())) return null;
        try { return Long.parseLong(auth.getName()); } catch (NumberFormatException e) { return null; }
    }

    private static String safeFilename(String name) {
        if (name == null || name.isBlank()) return "image";
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
