package com.martecyber.ares.files;

import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.files.dto.FileMetadataDto;
import com.martecyber.ares.users.OrgScopeService;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.time.OffsetDateTime;

@Service
public class FileService {

    private final FileMetadataRepository repo;
    private final S3Client s3;
    private final OrgScopeService orgScope;

    @Value("${ares.storage.s3.buckets.evidence}") private String evidenceBucket;

    public FileService(FileMetadataRepository repo, S3Client s3, OrgScopeService orgScope) {
        this.repo = repo;
        this.s3 = s3;
        this.orgScope = orgScope;
    }

    private static Authentication currentAuth() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    public Page<FileMetadataDto> list(Long organizationId, Long projectId, Long findingId, int page, int size) {
        var p = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200));
        var auth = currentAuth();
        java.util.Collection<Long> orgIds = null;
        if (organizationId != null) {
            orgScope.assertOrgAccess(auth, organizationId);
        } else if (projectId != null) {
            orgScope.assertProjectAccess(auth, projectId);
        } else if (!orgScope.isPlatformAdmin(auth)) {
            orgIds = orgScope.accessibleOrgIds(auth);
            if (orgIds.isEmpty()) return new PageImpl<>(java.util.List.of(), p, 0);
        }
        return repo.filter(organizationId, orgIds, projectId, findingId, p).map(FileMetadataDto::from);
    }

    public FileMetadataDto getMetadata(Long id) {
        FileMetadata meta = repo.findById(id).orElseThrow(() -> NotFoundException.of("file", id));
        orgScope.assertOrgAccess(currentAuth(), meta.getOrganizationId());
        return FileMetadataDto.from(meta);
    }

    @Transactional
    public FileMetadataDto upload(MultipartFile file, Long organizationId,
                                   Long projectId, Long findingId) throws IOException {
        orgScope.assertOrgAccess(currentAuth(), organizationId);
        var auth = currentAuth();
        Long uploadedBy = null;
        if (auth != null && auth.getName() != null && !"anonymousUser".equals(auth.getName())) {
            try { uploadedBy = Long.parseLong(auth.getName()); } catch (NumberFormatException ignored) {}
        }
        String objectKey = organizationId + "/" + System.currentTimeMillis() + "/" + file.getOriginalFilename();

        s3.putObject(
            PutObjectRequest.builder()
                .bucket(evidenceBucket)
                .key(objectKey)
                .contentType(file.getContentType())
                .build(),
            RequestBody.fromBytes(file.getBytes())
        );

        FileMetadata meta = new FileMetadata();
        meta.setOrganizationId(organizationId);
        meta.setProjectId(projectId);
        meta.setFindingId(findingId);
        meta.setOriginalName(file.getOriginalFilename());
        meta.setContentType(file.getContentType() != null ? file.getContentType() : "application/octet-stream");
        meta.setSizeBytes(file.getSize());
        meta.setBucket(evidenceBucket);
        meta.setObjectKey(objectKey);
        meta.setUploadedBy(uploadedBy);
        meta.setCreatedAt(OffsetDateTime.now());
        repo.save(meta);
        return FileMetadataDto.from(meta);
    }

    public byte[] download(Long id) throws IOException {
        FileMetadata meta = repo.findById(id).orElseThrow(() -> NotFoundException.of("file", id));
        orgScope.assertOrgAccess(currentAuth(), meta.getOrganizationId());
        return s3.getObjectAsBytes(
            GetObjectRequest.builder().bucket(meta.getBucket()).key(meta.getObjectKey()).build()
        ).asByteArray();
    }

    @Transactional
    public void delete(Long id) {
        FileMetadata meta = repo.findById(id).orElseThrow(() -> NotFoundException.of("file", id));
        orgScope.assertOrgAccess(currentAuth(), meta.getOrganizationId());
        s3.deleteObject(DeleteObjectRequest.builder().bucket(meta.getBucket()).key(meta.getObjectKey()).build());
        repo.deleteById(id);
    }
}
