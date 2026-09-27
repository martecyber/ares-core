package com.martecyber.ares.editorimages;

import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.storage.StorageService;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Images uploaded from the Markdown rich-text editor's toolbar/paste/drag-drop — stored via
 * {@link StorageService}, served back publicly by id (see {@link EditorImageController}) so a
 * plain {@code <img src>} works both in the editor's preview and in generated Word documents
 * (whose image-fetching code, {@code ReportGenerationService.decodeImage}, does a plain
 * unauthenticated HTTP GET).
 */
@Service
public class EditorImageService {

    private static final long MAX_IMAGE_BYTES = 5 * 1024 * 1024; // 5 MB

    private final EditorImageRepository repo;
    private final StorageService storage;

    @Value("${ares.storage.s3.buckets.editor-images}") private String bucket;

    public EditorImageService(EditorImageRepository repo, StorageService storage) {
        this.repo = repo;
        this.storage = storage;
    }

    public record UploadResult(Long id) {}
    public record ImageData(byte[] bytes, String contentType) {}

    @Transactional
    public UploadResult upload(MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("File is empty");
        if (file.getSize() > MAX_IMAGE_BYTES) throw new IllegalArgumentException("Image must be under 5 MB");
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new IllegalArgumentException("File must be an image");
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
        repo.save(img);
        return new UploadResult(img.getId());
    }

    public ImageData get(Long id) {
        EditorImage img = repo.findById(id).orElseThrow(() -> NotFoundException.of("editor_image", id));
        byte[] bytes = storage.get(img.getBucket(), img.getObjectKey());
        return new ImageData(bytes, img.getContentType());
    }

    private static Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || "anonymousUser".equals(auth.getName())) return null;
        try { return Long.parseLong(auth.getName()); } catch (NumberFormatException e) { return null; }
    }

    private static String safeFilename(String name) {
        if (name == null || name.isBlank()) return "image";
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
