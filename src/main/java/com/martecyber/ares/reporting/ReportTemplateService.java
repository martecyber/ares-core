package com.martecyber.ares.reporting;

import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.reporting.dto.ReportTemplateDto;
import com.martecyber.ares.reporting.dto.UpdateReportTemplateRequest;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ReportTemplateService {

    private final ReportTemplateRepository repo;
    private final ReportTemplateVariableRepository varRepo;
    private final ReportTemplateProjectTypeRepository typeAssocRepo;
    private final S3Client s3;

    @Value("${ares.storage.s3.buckets.report-templates}") private String templateBucket;

    public ReportTemplateService(
        ReportTemplateRepository repo,
        ReportTemplateVariableRepository varRepo,
        ReportTemplateProjectTypeRepository typeAssocRepo,
        S3Client s3
    ) {
        this.repo = repo;
        this.varRepo = varRepo;
        this.typeAssocRepo = typeAssocRepo;
        this.s3 = s3;
    }

    public List<ReportTemplateDto> listAll() {
        return repo.findByIsActiveTrueOrderByNameAsc().stream().map(this::toDto).toList();
    }

    public List<ReportTemplateDto> listForProjectType(Long projectTypeId) {
        return repo.findForProjectType(projectTypeId).stream().map(this::toDto).toList();
    }

    public ReportTemplateDto get(Long id) {
        return toDto(repo.findById(id).orElseThrow(() -> NotFoundException.of("report_template", id)));
    }

    @Transactional
    public ReportTemplateDto upload(MultipartFile file, String name, String description, boolean isGeneric) throws IOException {
        Long userId = resolveUserId();
        String objectKey = "templates/" + System.currentTimeMillis() + "/" + file.getOriginalFilename();

        s3.putObject(
            PutObjectRequest.builder()
                .bucket(templateBucket)
                .key(objectKey)
                .contentType(file.getContentType())
                .build(),
            RequestBody.fromBytes(file.getBytes())
        );

        OffsetDateTime now = OffsetDateTime.now();
        ReportTemplate t = new ReportTemplate();
        t.setName(name.trim());
        t.setDescription(description);
        t.setFormat("docx");
        t.setGeneric(isGeneric);
        t.setActive(true);
        t.setBucket(templateBucket);
        t.setObjectKey(objectKey);
        t.setOriginalFilename(file.getOriginalFilename());
        t.setCreatedBy(userId);
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        repo.save(t);
        return toDto(t);
    }

    @Transactional
    public ReportTemplateDto update(Long id, UpdateReportTemplateRequest req) {
        ReportTemplate t = repo.findById(id).orElseThrow(() -> NotFoundException.of("report_template", id));
        if (req.name() != null && !req.name().isBlank()) t.setName(req.name().trim());
        if (req.description() != null) t.setDescription(req.description());
        if (req.isGeneric() != null) t.setGeneric(req.isGeneric());
        if (req.isActive() != null) t.setActive(req.isActive());
        if (req.magicColor() != null) t.setMagicColor(req.magicColor().replaceAll("^#", "").toUpperCase());
        if (req.priorityColors() != null) t.setPriorityColors(req.priorityColors());
        t.setUpdatedAt(OffsetDateTime.now());
        repo.save(t);

        if (req.projectTypeIds() != null) {
            typeAssocRepo.deleteByTemplateId(id);
            req.projectTypeIds().forEach(typeId ->
                typeAssocRepo.save(new ReportTemplateProjectType(id, typeId)));
        }

        if (req.variables() != null) {
            varRepo.deleteByTemplateId(id);
            req.variables().forEach(v -> {
                ReportTemplateVariable var = new ReportTemplateVariable();
                var.setTemplateId(id);
                var.setVariableName(v.variableName());
                var.setSourceType(v.sourceType());
                var.setSystemField(v.systemField());
                var.setFieldTypeId(v.fieldTypeId());
                varRepo.save(var);
            });
        }

        return toDto(t);
    }

    @Transactional
    public ReportTemplateDto updateFile(Long id, MultipartFile file) throws IOException {
        ReportTemplate t = repo.findById(id).orElseThrow(() -> NotFoundException.of("report_template", id));
        String newKey = "templates/" + System.currentTimeMillis() + "/" + file.getOriginalFilename();
        s3.putObject(
            PutObjectRequest.builder().bucket(templateBucket).key(newKey)
                .contentType(file.getContentType()).build(),
            RequestBody.fromBytes(file.getBytes())
        );
        s3.deleteObject(DeleteObjectRequest.builder().bucket(t.getBucket()).key(t.getObjectKey()).build());
        t.setObjectKey(newKey);
        t.setOriginalFilename(file.getOriginalFilename());
        t.setUpdatedAt(OffsetDateTime.now());
        repo.save(t);
        return toDto(t);
    }

    @Transactional
    public void delete(Long id) {
        ReportTemplate t = repo.findById(id).orElseThrow(() -> NotFoundException.of("report_template", id));
        s3.deleteObject(DeleteObjectRequest.builder().bucket(t.getBucket()).key(t.getObjectKey()).build());
        repo.deleteById(id);
    }

    public byte[] downloadTemplate(Long id) {
        ReportTemplate t = repo.findById(id).orElseThrow(() -> NotFoundException.of("report_template", id));
        return s3.getObjectAsBytes(
            GetObjectRequest.builder().bucket(t.getBucket()).key(t.getObjectKey()).build()
        ).asByteArray();
    }

    private ReportTemplateDto toDto(ReportTemplate t) {
        List<Long> typeIds = typeAssocRepo.findByTemplateId(t.getId()).stream()
            .map(a -> a.getId().getProjectTypeId()).toList();
        List<ReportTemplateVariable> vars = varRepo.findByTemplateId(t.getId());
        return ReportTemplateDto.from(t, typeIds, vars);
    }

    private static Long resolveUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || "anonymousUser".equals(auth.getName())) return null;
        try { return Long.parseLong(auth.getName()); } catch (NumberFormatException e) { return null; }
    }
}
