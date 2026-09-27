package com.martecyber.ares.files;

import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.files.dto.FileMetadataDto;
import com.martecyber.ares.storage.StorageService;
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

import java.io.IOException;
import java.time.OffsetDateTime;

@Service
public class FileService {

    private final FileMetadataRepository repo;
    private final StorageService storage;
    private final OrgScopeService orgScope;

    @Value("${ares.storage.s3.buckets.evidence}") private String evidenceBucket;

    public FileService(FileMetadataRepository repo, StorageService storage, OrgScopeService orgScope) {
        this.repo = repo;
        this.storage = storage;
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

        storage.put(evidenceBucket, objectKey, file.getContentType(), file.getBytes());

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
        return storage.get(meta.getBucket(), meta.getObjectKey());
    }

    @Transactional
    public void delete(Long id) {
        FileMetadata meta = repo.findById(id).orElseThrow(() -> NotFoundException.of("file", id));
        orgScope.assertOrgAccess(currentAuth(), meta.getOrganizationId());
        storage.delete(meta.getBucket(), meta.getObjectKey());
        repo.deleteById(id);
    }
}
