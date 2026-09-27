package com.martecyber.ares.reporting;

import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.reporting.dto.ReportFieldTypeDto;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Service
public class ReportFieldTypeService {

    private final ReportFieldTypeRepository repo;
    private final ReportFieldTemplateRepository templateRepo;

    public ReportFieldTypeService(ReportFieldTypeRepository repo,
                                   ReportFieldTemplateRepository templateRepo) {
        this.repo = repo;
        this.templateRepo = templateRepo;
    }

    public List<ReportFieldTypeDto> listAll() {
        return repo.findAllByOrderBySortOrderAscLabelAsc().stream()
            .map(t -> ReportFieldTypeDto.from(t, templateRepo.findByFieldTypeIdOrderByIsDefaultDescNameAsc(t.getId())))
            .toList();
    }

    public ReportFieldTypeDto get(Long id) {
        return toDto(repo.findById(id).orElseThrow(() -> NotFoundException.of("report_field_type", id)));
    }

    @Transactional
    public ReportFieldTypeDto create(Map<String, Object> body) {
        OffsetDateTime now = OffsetDateTime.now();
        ReportFieldType t = new ReportFieldType();
        t.setName(str(body, "name"));
        t.setLabel(str(body, "label"));
        t.setDescription(str(body, "description"));
        t.setSortOrder(body.containsKey("sortOrder") ? ((Number) body.get("sortOrder")).intValue() : 0);
        t.setRequired(Boolean.TRUE.equals(body.get("required")));
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        return toDto(repo.save(t));
    }

    @Transactional
    public ReportFieldTypeDto update(Long id, Map<String, Object> body) {
        ReportFieldType t = repo.findById(id).orElseThrow(() -> NotFoundException.of("report_field_type", id));
        if (body.containsKey("label")) t.setLabel(str(body, "label"));
        if (body.containsKey("description")) t.setDescription(str(body, "description"));
        if (body.containsKey("sortOrder")) t.setSortOrder(((Number) body.get("sortOrder")).intValue());
        if (body.containsKey("required")) t.setRequired(Boolean.TRUE.equals(body.get("required")));
        t.setUpdatedAt(OffsetDateTime.now());
        return toDto(repo.save(t));
    }

    @Transactional
    public void delete(Long id) {
        if (!repo.existsById(id)) throw NotFoundException.of("report_field_type", id);
        repo.deleteById(id);
    }

    // ── Field templates ───────────────────────────────────────────────────────

    @Transactional
    public ReportFieldTypeDto.FieldTemplateDto createTemplate(Long typeId, Map<String, Object> body) {
        if (!repo.existsById(typeId)) throw NotFoundException.of("report_field_type", typeId);
        OffsetDateTime now = OffsetDateTime.now();
        ReportFieldTemplate t = new ReportFieldTemplate();
        t.setFieldTypeId(typeId);
        t.setName(str(body, "name"));
        t.setContent(str(body, "content"));
        t.setDefault(Boolean.TRUE.equals(body.get("isDefault")));
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        if (t.isDefault()) templateRepo.clearOtherDefaults(typeId, -1L);
        templateRepo.save(t);
        if (t.isDefault()) templateRepo.clearOtherDefaults(typeId, t.getId());
        return ReportFieldTypeDto.FieldTemplateDto.from(t);
    }

    @Transactional
    public ReportFieldTypeDto.FieldTemplateDto updateTemplate(Long typeId, Long tplId, Map<String, Object> body) {
        ReportFieldTemplate t = templateRepo.findById(tplId)
            .orElseThrow(() -> NotFoundException.of("report_field_template", tplId));
        if (!t.getFieldTypeId().equals(typeId)) throw new IllegalArgumentException("Template does not belong to this field type");
        if (body.containsKey("name")) t.setName(str(body, "name"));
        if (body.containsKey("content")) t.setContent(str(body, "content"));
        if (body.containsKey("isDefault")) {
            t.setDefault(Boolean.TRUE.equals(body.get("isDefault")));
            if (t.isDefault()) templateRepo.clearOtherDefaults(typeId, tplId);
        }
        t.setUpdatedAt(OffsetDateTime.now());
        return ReportFieldTypeDto.FieldTemplateDto.from(templateRepo.save(t));
    }

    @Transactional
    public void deleteTemplate(Long typeId, Long tplId) {
        ReportFieldTemplate t = templateRepo.findById(tplId)
            .orElseThrow(() -> NotFoundException.of("report_field_template", tplId));
        if (!t.getFieldTypeId().equals(typeId)) throw new IllegalArgumentException("Template does not belong to this field type");
        templateRepo.deleteById(tplId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ReportFieldTypeDto toDto(ReportFieldType t) {
        return ReportFieldTypeDto.from(t, templateRepo.findByFieldTypeIdOrderByIsDefaultDescNameAsc(t.getId()));
    }

    private static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof String s ? s : null;
    }
}
