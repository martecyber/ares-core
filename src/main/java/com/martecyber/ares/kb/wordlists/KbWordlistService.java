package com.martecyber.ares.kb.wordlists;

import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.common.PagedResponse;
import jakarta.annotation.PostConstruct;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class KbWordlistService {

    private static final Logger log = LoggerFactory.getLogger(KbWordlistService.class);

    private final KbWordlistRepository wordlistRepo;
    private final KbWordlistFolderRepository folderRepo;
    private final S3Client s3;

    @Value("${ares.storage.s3.buckets.wordlists}")
    private String bucket;

    public KbWordlistService(KbWordlistRepository wordlistRepo,
                              KbWordlistFolderRepository folderRepo,
                              S3Client s3) {
        this.wordlistRepo = wordlistRepo;
        this.folderRepo = folderRepo;
        this.s3 = s3;
    }

    @PostConstruct
    public void ensureBucketExists() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            log.info("S3 bucket '{}' not found, creating it…", bucket);
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            log.info("S3 bucket '{}' created.", bucket);
        } catch (Exception e) {
            log.warn("Could not verify/create S3 bucket '{}': {}", bucket, e.getMessage());
        }
    }

    // ── Folders ────────────────────────────────────────────────────────────

    public List<KbWordlistFolderDto> listFolders() {
        return folderRepo.findAll().stream()
            .map(KbWordlistFolderDto::from)
            .toList();
    }

    @Transactional
    public KbWordlistFolderDto createFolder(String name, Long parentId) {
        KbWordlistFolder folder = new KbWordlistFolder();
        folder.setName(name);
        folder.setParentId(parentId);
        folder.setCreatedAt(OffsetDateTime.now());
        return KbWordlistFolderDto.from(folderRepo.save(folder));
    }

    @Transactional
    public void deleteFolder(Long id) {
        if (!folderRepo.existsById(id)) throw NotFoundException.of("folder", id);
        deleteFolderRecursive(id);
    }

    private void deleteFolderRecursive(Long folderId) {
        // Recursively delete subfolders first
        for (KbWordlistFolder child : folderRepo.findByParentId(folderId)) {
            deleteFolderRecursive(child.getId());
        }
        // Delete all wordlists in this folder (S3 + DB)
        List<KbWordlist> wordlists = wordlistRepo.findByFolderId(folderId);
        for (KbWordlist wl : wordlists) {
            try {
                s3.deleteObject(software.amazon.awssdk.services.s3.model.DeleteObjectRequest.builder()
                    .bucket(bucket).key(wl.getObjectKey()).build());
            } catch (Exception ignored) {}
        }
        if (!wordlists.isEmpty()) wordlistRepo.deleteAll(wordlists);
        folderRepo.deleteById(folderId);
    }

    // ── Wordlists ──────────────────────────────────────────────────────────

    public PagedResponse<KbWordlistDto> listWordlists(
            Long folderId, boolean root, int page, int size,
            String q, String sortBy, String sortDir) {
        var direction = "desc".equalsIgnoreCase(sortDir) ? Sort.Direction.DESC : Sort.Direction.ASC;
        var field = resolveSort(sortBy);
        var pageable = PageRequest.of(page, size, Sort.by(direction, field));
        var pg = root
            ? wordlistRepo.findByFolderIdIsNullAndNameContainsIgnoreCase(q != null ? q : "", pageable)
            : (folderId != null
                ? wordlistRepo.findByFolderIdAndNameContainsIgnoreCase(folderId, q != null ? q : "", pageable)
                : wordlistRepo.findByNameContainsIgnoreCase(q != null ? q : "", pageable));
        return PagedResponse.of(pg, KbWordlistDto::from);
    }

    private static String resolveSort(String sortBy) {
        return switch (sortBy != null ? sortBy : "") {
            case "sizeBytes"  -> "sizeBytes";
            case "lineCount"  -> "lineCount";
            case "createdAt"  -> "createdAt";
            default           -> "name";
        };
    }

    @Transactional
    public KbWordlistDto upload(MultipartFile file, Long folderId, String description) throws IOException {
        byte[] bytes = file.getBytes();
        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "wordlist.txt";
        String objectKey = "wordlists/" + java.util.UUID.randomUUID() + "/" + fileName;

        KbWordlist entity = new KbWordlist();
        entity.setFolderId(folderId);
        entity.setName(fileName);
        entity.setDescription(description);
        entity.setObjectKey(objectKey);
        entity.setSizeBytes((long) bytes.length);
        entity.setSha256(computeSha256(bytes));
        entity.setLineCount(countLines(bytes));
        entity.setCreatedAt(OffsetDateTime.now());

        s3.putObject(
            PutObjectRequest.builder()
                .bucket(bucket)
                .key(objectKey)
                .contentType("text/plain")
                .build(),
            RequestBody.fromBytes(bytes)
        );

        return KbWordlistDto.from(wordlistRepo.save(entity));
    }

    @Transactional
    public List<KbWordlistDto> uploadZip(MultipartFile zip, Long folderId, boolean importAllTypes) throws IOException {
        List<KbWordlistDto> results = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(zip.getInputStream())) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }
                String entryName = entry.getName();
                // Only process .txt files or files without extension
                String baseName = entryName.contains("/")
                    ? entryName.substring(entryName.lastIndexOf('/') + 1)
                    : entryName;
                if (baseName.isEmpty()) {
                    zis.closeEntry();
                    continue;
                }
                boolean hasDot = baseName.contains(".");
                String lc = baseName.toLowerCase();
                boolean isWordlist = lc.endsWith(".txt") || lc.endsWith(".lst") || lc.endsWith(".dict");
                if (!importAllTypes && hasDot && !isWordlist) {
                    zis.closeEntry();
                    continue;
                }

                // Read entry bytes
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int read;
                while ((read = zis.read(buf)) != -1) {
                    baos.write(buf, 0, read);
                }
                byte[] bytes = baos.toByteArray();
                zis.closeEntry();

                String objectKey = "wordlists/" + java.util.UUID.randomUUID() + "/" + baseName;
                KbWordlist entity = new KbWordlist();
                entity.setFolderId(folderId);
                entity.setName(baseName);
                entity.setObjectKey(objectKey);
                entity.setSizeBytes((long) bytes.length);
                entity.setSha256(computeSha256(bytes));
                entity.setLineCount(countLines(bytes));
                entity.setCreatedAt(OffsetDateTime.now());

                s3.putObject(
                    PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(objectKey)
                        .contentType("text/plain")
                        .build(),
                    RequestBody.fromBytes(bytes)
                );

                results.add(KbWordlistDto.from(wordlistRepo.save(entity)));
            }
        }
        return results;
    }

    @Transactional
    public void delete(Long id) {
        KbWordlist entity = wordlistRepo.findById(id)
            .orElseThrow(() -> NotFoundException.of("wordlist", id));
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(entity.getObjectKey()).build());
        wordlistRepo.deleteById(id);
    }

    @Transactional
    public void bulkDelete(List<Long> ids) {
        List<KbWordlist> entities = wordlistRepo.findAllById(ids);
        for (KbWordlist entity : entities) {
            try {
                s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(entity.getObjectKey()).build());
            } catch (Exception ignored) {}
        }
        wordlistRepo.deleteAllById(ids);
    }

    public byte[] getContent(Long id) {
        KbWordlist entity = wordlistRepo.findById(id)
            .orElseThrow(() -> NotFoundException.of("wordlist", id));
        return s3.getObjectAsBytes(
            GetObjectRequest.builder()
                .bucket(bucket)
                .key(entity.getObjectKey())
                .build()
        ).asByteArray();
    }

    public KbWordlistDto getMetadata(Long id) {
        return wordlistRepo.findById(id)
            .map(KbWordlistDto::from)
            .orElseThrow(() -> NotFoundException.of("wordlist", id));
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static String computeSha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static long countLines(byte[] bytes) {
        long count = 0;
        for (byte b : bytes) {
            if (b == '\n') count++;
        }
        return count;
    }
}
