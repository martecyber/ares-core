package com.martecyber.ares.reporting;

import com.deepoove.poi.XWPFTemplate;
import com.martecyber.ares.common.PriorityLabels;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.ListBlock;
import org.commonmark.node.Node;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import com.deepoove.poi.config.Configure;
import com.deepoove.poi.data.Numberings;
import com.deepoove.poi.data.NumberingRenderData;
import com.deepoove.poi.plugin.table.LoopRowTableRenderPolicy;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.common.ReportGenerationException;
import com.martecyber.ares.organizations.Organization;
import com.martecyber.ares.organizations.OrganizationRepository;
import com.martecyber.ares.projects.ProjectMember;
import com.martecyber.ares.projects.ProjectMemberRepository;
import com.martecyber.ares.users.User;
import com.martecyber.ares.users.UserRepository;
import com.martecyber.ares.projects.Project;
import com.martecyber.ares.projects.ProjectRepository;
import com.martecyber.ares.projects.ProjectScopeEntry;
import com.martecyber.ares.projects.ProjectScopeEntryRepository;
import com.martecyber.ares.projects.ProjectType;
import com.martecyber.ares.projects.ProjectTypeRepository;
import com.martecyber.ares.affections.Affection;
import com.martecyber.ares.affections.AffectionAsset;
import com.martecyber.ares.affections.AffectionRepository;
import com.martecyber.ares.affections.AffectStatusHistory;
import com.martecyber.ares.affections.AffectStatusHistoryRepository;
import com.martecyber.ares.assets.Asset;
import com.martecyber.ares.assets.AssetRepository;
import com.martecyber.ares.detections.Detection;
import com.martecyber.ares.detections.DetectionIterationStat;
import com.martecyber.ares.detections.DetectionIterationStatRepository;
import com.martecyber.ares.detections.DetectionRepository;
import com.martecyber.ares.projects.ProjectRetestFinding;
import com.martecyber.ares.projects.ProjectRetestFindingRepository;
import com.martecyber.ares.findings.Finding;
import com.martecyber.ares.findings.FindingFieldType;
import com.martecyber.ares.findings.FindingFieldTypeRepository;
import com.martecyber.ares.findings.FindingRepository;
import com.martecyber.ares.findings.FindingScore;
import com.martecyber.ares.findings.FindingScoreRepository;
import com.martecyber.ares.findings.FindingScoreType;
import com.martecyber.ares.findings.FindingScoreTypeRepository;
import com.martecyber.ares.findings.FindingStatus;
import com.martecyber.ares.findings.FindingStatusRepository;
import com.martecyber.ares.references.ReferenceCatalog;
import com.martecyber.ares.references.ReferenceCatalogRepository;
import com.martecyber.ares.references.ReferenceEntry;
import com.martecyber.ares.references.ReferenceEntryRepository;
import com.martecyber.ares.reporting.dto.GenerateReportRequest;
import com.martecyber.ares.reporting.dto.ReportDto;
import jakarta.transaction.Transactional;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import com.martecyber.ares.storage.StorageService;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ReportGenerationService {

    private static final org.slf4j.Logger log =
        org.slf4j.LoggerFactory.getLogger(ReportGenerationService.class);

    private static final String CONTENT_TYPE_DOCX =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final ReportRepository reportRepo;
    private final ReportFindingRepository reportFindingRepo;
    private final ReportFieldRepository reportFieldRepo;
    private final ReportFieldTypeRepository fieldTypeRepo;
    private final ReportTemplateRepository templateRepo;
    private final ReportTemplateVariableRepository varRepo;
    private final FindingRepository findingRepo;
    private final FindingFieldTypeRepository findingFieldTypeRepo;
    private final FindingScoreRepository findingScoreRepo;
    private final FindingScoreTypeRepository findingScoreTypeRepo;
    private final FindingStatusRepository findingStatusRepo;
    private final AffectionRepository affectionRepo;
    private final ReferenceEntryRepository refEntryRepo;
    private final ReferenceCatalogRepository refCatalogRepo;
    private final OrganizationRepository organizationRepo;
    private final ProjectMemberRepository projectMemberRepo;
    private final UserRepository userRepo;
    private final ProjectRepository projectRepo;
    private final ProjectScopeEntryRepository scopeEntryRepo;
    private final ProjectTypeRepository engTypeRepo;
    private final ProjectRetestFindingRepository retestFindingRepo;
    private final AffectStatusHistoryRepository affectHistoryRepo;
    private final AssetRepository assetRepo;
    private final DetectionRepository detectionRepo;
    private final DetectionIterationStatRepository detectionStatRepo;
    private final StorageService storage;

    @Value("${ares.storage.s3.buckets.report-templates}") private String templateBucket;
    @Value("${ares.storage.s3.buckets.exports}") private String exportsBucket;

    public ReportGenerationService(
        ReportRepository reportRepo,
        ReportFindingRepository reportFindingRepo,
        ReportFieldRepository reportFieldRepo,
        ReportFieldTypeRepository fieldTypeRepo,
        ReportTemplateRepository templateRepo,
        ReportTemplateVariableRepository varRepo,
        FindingRepository findingRepo,
        FindingFieldTypeRepository findingFieldTypeRepo,
        FindingScoreRepository findingScoreRepo,
        FindingScoreTypeRepository findingScoreTypeRepo,
        FindingStatusRepository findingStatusRepo,
        AffectionRepository affectionRepo,
        ReferenceEntryRepository refEntryRepo,
        ReferenceCatalogRepository refCatalogRepo,
        OrganizationRepository organizationRepo,
        ProjectMemberRepository projectMemberRepo,
        UserRepository userRepo,
        ProjectRepository projectRepo,
        ProjectScopeEntryRepository scopeEntryRepo,
        ProjectTypeRepository engTypeRepo,
        ProjectRetestFindingRepository retestFindingRepo,
        AffectStatusHistoryRepository affectHistoryRepo,
        AssetRepository assetRepo,
        DetectionRepository detectionRepo,
        DetectionIterationStatRepository detectionStatRepo,
        StorageService storage
    ) {
        this.reportRepo = reportRepo;
        this.reportFindingRepo = reportFindingRepo;
        this.reportFieldRepo = reportFieldRepo;
        this.fieldTypeRepo = fieldTypeRepo;
        this.templateRepo = templateRepo;
        this.varRepo = varRepo;
        this.findingRepo = findingRepo;
        this.findingFieldTypeRepo = findingFieldTypeRepo;
        this.findingScoreRepo = findingScoreRepo;
        this.findingScoreTypeRepo = findingScoreTypeRepo;
        this.findingStatusRepo = findingStatusRepo;
        this.affectionRepo = affectionRepo;
        this.refEntryRepo = refEntryRepo;
        this.refCatalogRepo = refCatalogRepo;
        this.organizationRepo = organizationRepo;
        this.projectMemberRepo = projectMemberRepo;
        this.userRepo = userRepo;
        this.projectRepo = projectRepo;
        this.scopeEntryRepo = scopeEntryRepo;
        this.engTypeRepo = engTypeRepo;
        this.retestFindingRepo = retestFindingRepo;
        this.affectHistoryRepo = affectHistoryRepo;
        this.assetRepo = assetRepo;
        this.detectionRepo = detectionRepo;
        this.detectionStatRepo = detectionStatRepo;
        this.storage = storage;
    }

    /** True when {@code typeId} is the RETEST master or any user-defined subtype of it. */
    private boolean isRetestType(Long typeId) {
        if (typeId == null) return false;
        ProjectType type = engTypeRepo.findById(typeId).orElse(null);
        if (type == null) return false;
        if ("RETEST".equals(type.getCode())) return true;
        if (type.getSupertypeId() == null) return false;
        return engTypeRepo.findById(type.getSupertypeId())
            .map(t -> "RETEST".equals(t.getCode()))
            .orElse(false);
    }

    /**
     * Creates a report record with selected findings and custom field content.
     * No document is generated — call generateDocument() separately.
     */
    @Transactional
    public ReportDto create(GenerateReportRequest req) {
        Long userId = resolveUserId();
        OffsetDateTime now = OffsetDateTime.now();

        Project project = projectRepo.findById(req.projectId())
            .orElseThrow(() -> NotFoundException.of("project", req.projectId()));

        // Determine findings: RETEST (linked findings, from their own origin projects) >
        // user-selected > iteration range (MONITOR) > ready-to-report fallback
        List<Finding> findings;
        if (isRetestType(project.getTypeId())) {
            List<Long> linkedIds = retestFindingRepo.findByRetestProjectIdOrderByLinkedAtDesc(project.getId())
                .stream().map(ProjectRetestFinding::getFindingId).toList();
            findings = findingRepo.findAllById(linkedIds);
        } else if (req.findingIds() != null) {
            // An explicit empty list (as opposed to the field being entirely absent, which falls
            // through to the strategies below) means the caller deliberately wants a report with
            // no findings — allowed, not a validation error.
            findings = findingRepo.findAllById(req.findingIds()).stream()
                .filter(f -> f.getProjectId().equals(req.projectId()) && !f.isDraft())
                .toList();
        } else if (req.iterationFrom() != null && req.iterationTo() != null) {
            findings = findingRepo.findByProjectAndIterationRange(
                req.projectId(), req.iterationFrom(), req.iterationTo());
        } else if (req.iterationFrom() != null) {
            findings = findingRepo.findByProjectAndIterationRange(
                req.projectId(), req.iterationFrom(), req.iterationFrom());
        } else {
            findings = findingRepo.findReadyToReport(req.projectId());
        }

        String title = (req.title() != null && !req.title().isBlank())
            ? req.title().trim()
            : project.getName() + " — Security Assessment Report";

        Report report = new Report();
        report.setOrganizationId(req.organizationId());
        report.setProjectId(req.projectId());
        report.setType("assessment");
        report.setFormat("docx");
        report.setStatus("draft");
        report.setTitle(title);
        report.setGeneratedBy(userId);
        report.setCreatedAt(now);
        report.setCompletedAt(now);
        reportRepo.save(report);

        findings.forEach(f -> reportFindingRepo.save(new ReportFinding(report.getId(), f.getId())));

        Map<String, String> customFields = req.customFields() != null ? req.customFields() : Map.of();
        Map<String, Long> fieldTypeByName = fieldTypeRepo.findAllByOrderBySortOrderAscLabelAsc().stream()
            .collect(Collectors.toMap(ReportFieldType::getName, ReportFieldType::getId));
        customFields.forEach((slug, content) -> {
            ReportField rf = new ReportField();
            rf.setReportId(report.getId());
            rf.setFieldTypeId(fieldTypeByName.get(slug));
            rf.setFieldName(slug);
            rf.setContent(content);
            rf.setCreatedAt(now);
            rf.setUpdatedAt(now);
            reportFieldRepo.save(rf);
        });

        return ReportDto.from(report, findings.size(), reportFieldRepo.findByReportId(report.getId()));
    }

    /** Generates a DOCX from the saved report data and uploads it to S3. */
    @Transactional
    public ReportDto generateDocument(Long reportId, Long templateId) {
        Report report = reportRepo.findById(reportId).orElseThrow(() -> NotFoundException.of("report", reportId));
        Project project = projectRepo.findById(report.getProjectId())
            .orElseThrow(() -> NotFoundException.of("project", report.getProjectId()));

        List<Long> findingIds = reportFindingRepo.findByReportId(reportId).stream()
            .map(ReportFinding::getFindingId).toList();
        List<Finding> findings = findingRepo.findAllById(findingIds);

        List<ReportField> savedFields = reportFieldRepo.findByReportId(reportId);
        Map<String, String> customFields = savedFields.stream()
            .collect(Collectors.toMap(ReportField::getFieldName, f -> f.getContent() != null ? f.getContent() : ""));

        ReportTemplate template = resolveTemplate(templateId, project);
        OffsetDateTime now = OffsetDateTime.now();
        try {
            byte[] docx = generateDocx(template, report, project, findings, customFields);
            String objectKey = report.getOrganizationId() + "/reports/" + now.toEpochSecond() + "_" + reportId + ".docx";
            storage.put(exportsBucket, objectKey, CONTENT_TYPE_DOCX, docx);
            report.setTemplateId(template.getId());
            report.setReportBucket(exportsBucket);
            report.setReportObjectKey(objectKey);
            report.setCompletedAt(now);
            reportRepo.save(report);
        } catch (com.deepoove.poi.exception.ResolverException ex) {
            String msg = ex.getMessage() != null && ex.getMessage().contains("Mismatched start/end tags")
                ? "Error en la plantilla: etiquetas de bucle desparejadas. " +
                  "En tablas, {{#tag}} debe estar en la primera celda y {{/tag}} en la última celda de la MISMA fila. " +
                  "Detalle: " + ex.getMessage()
                : "Error en la plantilla: " + ex.getMessage();
            report.setError(msg);
            reportRepo.save(report);
            throw new ReportGenerationException(msg, ex);
        } catch (ReportGenerationException ex) {
            throw ex;
        } catch (Exception ex) {
            String msg = "Error al generar el documento: " + ex.getMessage();
            report.setError(msg);
            reportRepo.save(report);
            throw new ReportGenerationException(msg, ex);
        }
        return ReportDto.from(report, findings.size(), savedFields);
    }

    /** Builds a structured JSON export of all report data. */
    public Map<String, Object> exportJson(Long reportId) {
        Report report = reportRepo.findById(reportId).orElseThrow(() -> NotFoundException.of("report", reportId));
        Project project = projectRepo.findById(report.getProjectId())
            .orElseThrow(() -> NotFoundException.of("project", report.getProjectId()));

        List<Long> findingIds = reportFindingRepo.findByReportId(reportId).stream()
            .map(ReportFinding::getFindingId).toList();
        List<Finding> findings = findingRepo.findAllById(findingIds);
        List<ReportField> savedFields = reportFieldRepo.findByReportId(reportId);

        // Batch-load lookup tables upfront
        Map<Long, String> scoreTypeNames = findingScoreTypeRepo.findAll().stream()
            .collect(Collectors.toMap(FindingScoreType::getId, FindingScoreType::getTitle));
        Map<Long, ReferenceCatalog> catalogsById = refCatalogRepo.findAll().stream()
            .collect(Collectors.toMap(ReferenceCatalog::getId, c -> c));

        Map<String, Object> root = new LinkedHashMap<>();

        Organization organization = organizationRepo.findById(report.getOrganizationId()).orElse(null);
        if (organization != null) {
            Map<String, Object> oMap = new LinkedHashMap<>();
            oMap.put("id", organization.getId());
            oMap.put("name", organization.getName());
            oMap.put("slug", organization.getSlug());
            root.put("organization", oMap);
        }

        Map<String, Object> rMap = new LinkedHashMap<>();
        rMap.put("id", report.getId());
        rMap.put("title", report.getTitle());
        rMap.put("status", report.getStatus());
        rMap.put("createdAt", report.getCreatedAt());
        root.put("report", rMap);

        List<Map<String, Object>> scopeList = scopeEntryRepo
            .findByProjectIdOrderByCreatedAtAsc(project.getId()).stream()
            .map(s -> {
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("kind", s.getKind());
                sm.put("value", s.getValue());
                sm.put("inScope", s.isInScope() ? "IN" : "OUT");
                if (s.getNotes() != null) sm.put("notes", s.getNotes());
                return sm;
            }).toList();

        Map<String, Object> eMap = new LinkedHashMap<>();
        eMap.put("id", project.getId());
        eMap.put("name", project.getName());
        eMap.put("code", project.getCode());
        eMap.put("startDate", project.getStartDate());
        eMap.put("endDate", project.getEndDate());
        eMap.put("scope", scopeList);
        root.put("project", eMap);

        List<ProjectMember> members = projectMemberRepo.findByIdProjectId(project.getId());
        Map<Long, User> usersById = userRepo.findAllById(
            members.stream().map(m -> m.getId().getUserId()).toList()
        ).stream().collect(Collectors.toMap(User::getId, u -> u));
        List<Map<String, Object>> teamList = members.stream().map(m -> {
            User u = usersById.get(m.getId().getUserId());
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("userId", m.getId().getUserId());
            tm.put("role", m.getId().getRole());
            tm.put("displayName", u != null ? u.getDisplayName() : "");
            tm.put("email", u != null ? u.getEmail() : "");
            tm.put("addedAt", m.getAddedAt());
            return tm;
        }).toList();
        root.put("team", teamList);

        Map<String, String> fieldsMap = new LinkedHashMap<>();
        savedFields.forEach(f -> fieldsMap.put(f.getFieldName(), f.getContent()));
        root.put("fields", fieldsMap);

        List<Map<String, Object>> findingsList = findings.stream().map(f -> {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("code", f.getCode());
            fm.put("title", f.getTitle());
            fm.put("severity", f.getSeverity() != null ? f.getSeverity().toUpperCase() : "");
            fm.put("status", f.getStatusId());

            // Custom fields, already keyed by slug (finding_field_type.name) in the jsonb column
            fm.put("fields", com.martecyber.ares.findings.FindingFields.read(f));

            // Scores
            List<FindingScore> scores = findingScoreRepo.findByFindingId(f.getId());
            FindingScore defScore = scores.stream().filter(FindingScore::isDefault).findFirst().orElse(null);
            if (defScore != null) {
                Map<String, Object> ds = new LinkedHashMap<>();
                ds.put("type", scoreTypeNames.getOrDefault(defScore.getTypeId(), "unknown"));
                ds.put("score", defScore.getScore());
                if (defScore.getVector() != null) ds.put("vector", defScore.getVector());
                if (defScore.getComment() != null) ds.put("comment", defScore.getComment());
                fm.put("defaultScore", ds);
            }
            List<Map<String, Object>> scoresList = scores.stream().map(s -> {
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("type", scoreTypeNames.getOrDefault(s.getTypeId(), "unknown"));
                sm.put("score", s.getScore());
                sm.put("isDefault", s.isDefault());
                if (s.getVector() != null) sm.put("vector", s.getVector());
                if (s.getComment() != null) sm.put("comment", s.getComment());
                return sm;
            }).toList();
            fm.put("scores", scoresList);

            // References — full list + one pre-filtered list per catalog (referencesCwe, referencesCapec, …)
            List<ReferenceEntry> refs = refEntryRepo.findByFindingId(f.getId());
            List<Map<String, Object>> refsList = refs.stream().map(r -> {
                Map<String, Object> rm = new LinkedHashMap<>();
                ReferenceCatalog cat = catalogsById.get(r.getCatalogId());
                rm.put("catalog", cat != null ? cat.getCode() : "unknown");
                rm.put("title", r.getTitle());
                if (r.getDescription() != null) rm.put("description", r.getDescription());
                return rm;
            }).toList();
            fm.put("references", refsList);
            refsList.stream()
                .collect(Collectors.groupingBy(r -> (String) r.get("catalog")))
                .forEach((catalogCode, list) -> {
                    if (!catalogCode.isEmpty() && !"unknown".equals(catalogCode)) {
                        String key = "references" + catalogCode.charAt(0) + catalogCode.substring(1).toLowerCase();
                        fm.put(key, list);
                    }
                });

            // Affections — sorted ASC for sequential code assignment
            List<Affection> affections = affectionRepo.findByFindingIdWithAssets(f.getId())
                .stream()
                .sorted(Comparator.comparing(Affection::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
            Map<Long, Map<String, Object>> uniqueAffectedAssets = new LinkedHashMap<>();
            List<Map<String, Object>> affectionsList = affections.stream().map(a -> {
                Map<String, Object> am = new LinkedHashMap<>();
                am.put("code", a.getCode() != null ? a.getCode() : "");
                if (a.getTitle() != null) am.put("title", a.getTitle());
                if (a.getDescription() != null) am.put("description", a.getDescription());
                am.put("status", a.getStatus());

                List<Map<String, Object>> affects = a.getAssetLinks().stream()
                    .filter(al -> "affects".equals(al.getRole()))
                    .map(al -> {
                        Map<String, Object> m = buildAssetMap(al.getAsset(), al.getAssetId());
                        m.put("status", al.getStatus());
                        uniqueAffectedAssets.computeIfAbsent(al.getAssetId(),
                            id -> buildAssetMap(al.getAsset(), id));
                        return m;
                    }).toList();
                am.put("affects", affects);

                List<Map<String, Object>> detectedAt = a.getAssetLinks().stream()
                    .filter(al -> "detected_at".equals(al.getRole()))
                    .map(al -> {
                        Map<String, Object> m = buildAssetMap(al.getAsset(), al.getAssetId());
                        if (al.getObservedAt() != null) m.put("observedAt", al.getObservedAt());
                        return m;
                    }).toList();
                am.put("detectedAt", detectedAt);

                return am;
            }).toList();
            fm.put("affections", affectionsList);
            fm.put("affectedAssets", new ArrayList<>(uniqueAffectedAssets.values()));

            List<Map<String, Object>> retestHistory = buildRetestHistory(f, project);
            fm.put("retestHistory", retestHistory);
            fm.put("retestNote", buildRetestNote(retestHistory));

            return fm;
        }).toList();
        root.put("findings", findingsList);

        // detections (+ per-iteration breakdown) — same scoping rule as generateDocx: the
        // iteration labels carried by the findings above when present, else the whole project.
        TreeSet<String> iterationLabels = findingIterationLabels(findings);
        List<Detection> detections = resolveReportDetections(project.getId(), iterationLabels);
        root.put("detections", buildDetectionsList(detections));
        root.put("detectionsByIteration", buildDetectionsByIteration(project.getId(), iterationLabels));

        return root;
    }

    /**
     * Fixes split template tags caused by Word splitting {{...}} across multiple runs
     * with different formatting. Merges runs until each {{...}} is in a single run.
     */
    private byte[] repairSplitTemplateTags(byte[] docxBytes) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docxBytes))) {
            repairBodyElements(doc.getBodyElements());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        }
    }

    private void repairBodyElements(List<IBodyElement> elements) {
        for (IBodyElement el : elements) {
            if (el instanceof XWPFParagraph) {
                repairParagraphRuns((XWPFParagraph) el);
            } else if (el instanceof XWPFTable) {
                for (XWPFTableRow row : ((XWPFTable) el).getRows())
                    for (XWPFTableCell cell : row.getTableCells())
                        repairBodyElements(cell.getBodyElements());
            }
        }
    }

    private void repairParagraphRuns(XWPFParagraph para) {
        boolean merged = true;
        while (merged) {
            merged = false;
            List<XWPFRun> runs = para.getRuns();
            for (int i = 0; i < runs.size() - 1; i++) {
                String text = runs.get(i).getText(0);
                if (text == null) text = "";
                boolean needsMerge = text.endsWith("{")
                    || (text.contains("{{") && !text.contains("}}"));
                if (needsMerge) {
                    String next = runs.get(i + 1).getText(0);
                    if (next == null || next.isEmpty()) continue; // skip empty runs, avoid infinite loop
                    runs.get(i).setText(text + next, 0);
                    runs.get(i + 1).setText("", 0);
                    merged = true;
                    break;
                }
            }
        }
    }

    /** For RETEST reports: status-change history (with notes) recorded on this finding's
     *  affections since it was linked into the retest project's scope. Empty for normal reports. */
    private List<Map<String, Object>> buildRetestHistory(Finding f, Project reportProject) {
        if (!isRetestType(reportProject.getTypeId())) return List.of();
        ProjectRetestFinding link = retestFindingRepo
            .findByRetestProjectIdAndFindingId(reportProject.getId(), f.getId()).orElse(null);
        if (link == null) return List.of();
        List<Long> affectionIds = affectionRepo.findByFindingIdWithAssets(f.getId()).stream()
            .map(Affection::getId).toList();
        if (affectionIds.isEmpty()) return List.of();
        List<AffectStatusHistory> history =
            affectHistoryRepo.findByAffectionIdInAndChangedAtAfter(affectionIds, link.getLinkedAt());
        if (history.isEmpty()) return List.of();
        Map<Long, Asset> assetsById = assetRepo.findAllById(
            history.stream().map(AffectStatusHistory::getAssetId).distinct().toList()
        ).stream().collect(Collectors.toMap(Asset::getId, a -> a));
        return history.stream().map(h -> {
            Map<String, Object> m = new LinkedHashMap<>();
            Asset asset = assetsById.get(h.getAssetId());
            m.put("assetType", asset != null ? asset.getType() : "");
            m.put("identifier", asset != null ? asset.getIdentifier() : "");
            m.put("fromStatus", h.getFromStatus() != null ? h.getFromStatus() : "");
            m.put("toStatus", h.getToStatus() != null ? h.getToStatus() : "");
            m.put("note", h.getNote() != null ? h.getNote() : "");
            m.put("changedByName", h.getChangedByName() != null ? h.getChangedByName() : "");
            m.put("changedAt", h.getChangedAt() != null ? h.getChangedAt().toString() : "");
            return m;
        }).toList();
    }

    /** Flattened, human-readable rendering of {@link #buildRetestHistory}, for use as a
     *  plain scalar token ({{retestNote}}) alongside the structured {{retestHistory}} loop. */
    private String buildRetestNote(List<Map<String, Object>> retestHistory) {
        return retestHistory.stream()
            .filter(h -> {
                String note = (String) h.get("note");
                return note != null && !note.isBlank();
            })
            .map(h -> "[" + h.get("identifier") + "] " + h.get("toStatus") + ": " + h.get("note"))
            .collect(Collectors.joining("\n"));
    }

    private Map<String, Object> buildAssetMap(Asset asset, Long assetId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", assetId);
        if (asset != null) {
            m.put("code", asset.getCode());
            m.put("type", asset.getType());
            m.put("identifier", asset.getIdentifier());
        }
        return m;
    }

    // ── Detections (report template data) ───────────────────────────────────────

    /** Distinct, non-null iteration labels carried by this report's findings — non-empty only
     *  for MONITOR projects generated with an iteration range. Empty means "no iteration scope",
     *  i.e. detections should not be narrowed. */
    private TreeSet<String> findingIterationLabels(List<Finding> findings) {
        return findings.stream()
            .map(Finding::getIterationLabel)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(TreeSet::new));
    }

    /** Detections available to the report template: scoped to the same iteration window as the
     *  findings above (via DetectionIterationStat) when one exists, else every detection in the
     *  project — detection data is meant to be available, not curated the way findings are. */
    private List<Detection> resolveReportDetections(Long projectId, TreeSet<String> iterationLabels) {
        if (iterationLabels.isEmpty()) {
            return detectionRepo.findByProjectId(projectId);
        }
        return detectionRepo.findByProjectAndIterationLabelRange(
            projectId, iterationLabels.first(), iterationLabels.last());
    }

    private List<Map<String, Object>> buildDetectionsList(List<Detection> detections) {
        Map<Long, Asset> assetsById = assetRepo.findAllById(
            detections.stream().map(Detection::getAssetId).filter(Objects::nonNull).distinct().toList()
        ).stream().collect(Collectors.toMap(Asset::getId, a -> a));

        return detections.stream()
            .sorted(Comparator.comparing(Detection::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
            .map(d -> {
                Map<String, Object> dm = new LinkedHashMap<>();
                dm.put("id", d.getId());
                dm.put("title", d.getTitle() != null ? d.getTitle() : "");
                dm.put("severity", d.getSeverity() != null ? d.getSeverity().toUpperCase() : "");
                dm.put("status", d.getStatus() != null ? d.getStatus() : "");
                dm.put("sourceType", d.getSourceType() != null ? d.getSourceType() : "");
                dm.put("occurrenceCount", String.valueOf(d.getOccurrenceCount()));
                dm.put("firstSeen", d.getCreatedAt() != null ? d.getCreatedAt().toString() : "");
                dm.put("lastSeen", d.getLastSeen() != null ? d.getLastSeen().toString() : "");
                dm.put("asset", buildAssetMap(
                    d.getAssetId() != null ? assetsById.get(d.getAssetId()) : null, d.getAssetId()));
                return dm;
            }).toList();
    }

    /** Detections that entered each area during each iteration — same aggregation
     *  MonitorStatsService.compute() uses, filtered to iterationLabels when non-empty. */
    private List<Map<String, Object>> buildDetectionsByIteration(Long projectId, TreeSet<String> iterationLabels) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String label : detectionStatRepo.findDistinctIterationLabels(projectId)) {
            if (!iterationLabels.isEmpty() && !iterationLabels.contains(label)) continue;
            Map<String, Long> areaMap = new LinkedHashMap<>();
            for (Object[] row : detectionStatRepo.countByProjectAndLabelGroupByArea(projectId, label)) {
                areaMap.put((String) row[0], ((Number) row[1]).longValue());
            }
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("label", label);
            rm.put("opened",    String.valueOf(areaMap.getOrDefault(DetectionIterationStat.AREA_OPEN, 0L)));
            rm.put("escalated", String.valueOf(areaMap.getOrDefault(DetectionIterationStat.AREA_ESCALATED, 0L)));
            rm.put("closed",    String.valueOf(areaMap.getOrDefault(DetectionIterationStat.AREA_CLOSED, 0L)));
            rows.add(rm);
        }
        return rows;
    }

    // ── Template analysis ─────────────────────────────────────────────────────

    public List<Map<String, Object>> analyzeTemplate(Long templateId) throws Exception {
        ReportTemplate template = templateRepo.findById(templateId)
            .orElseThrow(() -> NotFoundException.of("report_template", templateId));
        byte[] bytes = storage.get(template.getBucket(), template.getObjectKey());

        // Extract text from the DOCX and find all {{...}} patterns
        Set<String> vars = extractTemplateVars(bytes);

        // Known system variable paths (dot-notation mirrors JSON structure)
        Set<String> systemVars = Set.of(
            // top-level objects / arrays
            "organization", "report", "project", "team", "team_list",
            "project_scope_list", "projectScope", "fields", "findings",
            "detections", "detectionsByIteration",
            // organization.*
            "organization.id", "organization.name", "organization.slug",
            // report.*
            "report.id", "report.title", "report.status",
            "report.createdAt", "report.created_at", "report.date",
            // project.*
            "project.id", "project.name", "project.code",
            "project.startDate", "project.start_date",
            "project.endDate", "project.end_date",
            "project.scope", "kind", "value", "notes",
            // finding.*
            "affections.count",
            // computed top-level counts
            "findingsCount", "findingsCountCritical", "findingsCountHigh",
            "findingsCountMedium", "findingsCountLow", "findingsCountInfo",
            "detectionsCount", "detectionsCountCritical", "detectionsCountHigh",
            "detectionsCountMedium", "detectionsCountLow", "detectionsCountInfo",
            // team loop vars
            "userId", "role", "displayName", "email", "addedAt",
            // finding loop vars
            "code", "title", "severity", "status", "affectedAssetsCount",
            "defaultScore", "defaultScore.type", "defaultScore.score", "defaultScore.vector",
            "scores", "references", "affections", "affectedAssets",
            // scores loop
            "type", "score", "isDefault", "vector",
            // references loop
            "catalog", "description",
            // affections loop (computed counts + nested arrays)
            "affectedCount", "stillAffectedCount", "affects", "detectedAt",
            // affects / affectedAssets / detectedAt loop
            "id", "identifier", "observedAt",
            // RETEST-only: per-finding verification note + status-change history since linking
            "retestNote", "retestHistory",
            // retestHistory loop vars
            "assetType", "fromStatus", "toStatus", "note", "changedByName", "changedAt",
            // detections loop vars (id/title/severity/status shared with finding loop vars above)
            "sourceType", "occurrenceCount", "firstSeen", "lastSeen", "asset",
            "asset.id", "asset.code", "asset.type", "asset.identifier",
            // detectionsByIteration loop vars
            "label", "opened", "escalated", "closed"
        );

        // Report-level and finding-level field slugs → accessible as fields.<slug>
        Set<String> reportFieldSlugs = fieldTypeRepo.findAllByOrderBySortOrderAscLabelAsc().stream()
            .map(ReportFieldType::getName).collect(Collectors.toSet());
        Map<String, String> reportFieldLabels = fieldTypeRepo.findAllByOrderBySortOrderAscLabelAsc().stream()
            .collect(Collectors.toMap(ReportFieldType::getName, ReportFieldType::getLabel));
        Set<String> findingFieldSlugs = findingFieldTypeRepo.findAllByOrderBySortOrderAscTitleAsc().stream()
            .map(FindingFieldType::getName).collect(Collectors.toSet());

        return vars.stream().map(v -> {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("variable", v);

            // Exact match, known dot-prefix, or pre-rendered _list variants
            if (systemVars.contains(v)
                    || v.startsWith("organization.") || v.startsWith("report.")
                    || v.startsWith("project.") || v.startsWith("defaultScore.")
                    || v.startsWith("asset.") || v.startsWith("references")) {
                info.put("type", "system");
                info.put("resolved", true);

            // fields.<slug> — report field or finding field
            } else if (v.startsWith("fields.")) {
                String slug = v.substring(7);
                if (reportFieldLabels.containsKey(slug)) {
                    info.put("type", "report_field");
                    info.put("fieldLabel", reportFieldLabels.get(slug));
                } else if (findingFieldSlugs.contains(slug)) {
                    info.put("type", "report_field");
                    info.put("fieldLabel", slug);
                } else {
                    info.put("type", "unknown");
                }
                info.put("resolved", !info.get("type").equals("unknown"));

            } else {
                info.put("type", "unknown");
                info.put("resolved", false);
            }
            return info;
        }).toList();
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private byte[] generateDocx(ReportTemplate template, Report report, Project project,
                                  List<Finding> findings, Map<String, String> customFields) throws Exception {
        byte[] templateBytes = storage.get(template.getBucket(), template.getObjectKey());

        // Batch-load lookup tables (same as exportJson)
        Map<Long, String> scoreTypeNames = findingScoreTypeRepo.findAll().stream()
            .collect(Collectors.toMap(FindingScoreType::getId, FindingScoreType::getTitle));
        Map<Long, ReferenceCatalog> catalogsById = refCatalogRepo.findAll().stream()
            .collect(Collectors.toMap(ReferenceCatalog::getId, c -> c));

        // Model mirrors the JSON export exactly
        Map<String, Object> model = new LinkedHashMap<>();

        // organization
        Organization org = organizationRepo.findById(report.getOrganizationId()).orElse(null);
        Map<String, Object> oMap = new LinkedHashMap<>();
        oMap.put("id", org != null ? org.getId() : null);
        oMap.put("name", org != null ? org.getName() : "");
        oMap.put("slug", org != null ? org.getSlug() : "");
        model.put("organization", oMap);

        // report  (+ computed date in display format)
        Map<String, Object> rMap = new LinkedHashMap<>();
        rMap.put("id", report.getId());
        rMap.put("title", report.getTitle() != null ? report.getTitle() : "");
        rMap.put("status", report.getStatus() != null ? report.getStatus() : "");
        String createdAtStr = report.getCreatedAt() != null ? report.getCreatedAt().toString() : "";
        rMap.put("createdAt", createdAtStr);
        rMap.put("created_at", createdAtStr);
        rMap.put("date", DateTimeFormatter.ofPattern("dd/MM/yyyy").format(OffsetDateTime.now()));
        model.put("report", rMap);

        // project
        Map<String, Object> pMap = new LinkedHashMap<>();
        pMap.put("id", project.getId());
        pMap.put("name", project.getName() != null ? project.getName() : "");
        pMap.put("code", project.getCode() != null ? project.getCode() : "");
        String startDateStr = project.getStartDate() != null ? project.getStartDate().toString() : "";
        String endDateStr   = project.getEndDate()   != null ? project.getEndDate().toString()   : "";
        pMap.put("startDate",  startDateStr);
        pMap.put("start_date", startDateStr);
        pMap.put("endDate",    endDateStr);
        pMap.put("end_date",   endDateStr);
        List<ProjectScopeEntry> scopeEntries = scopeEntryRepo.findByProjectIdOrderByCreatedAtAsc(project.getId());
        List<Map<String, Object>> scopeList = scopeEntries.stream().map(s -> {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("kind", s.getKind() != null ? s.getKind() : "");
            sm.put("value", s.getValue() != null ? s.getValue() : "");
            sm.put("inScope", s.isInScope() ? "IN" : "OUT");
            sm.put("notes", s.getNotes() != null ? s.getNotes() : "");
            return sm;
        }).toList();
        pMap.put("scope", scopeList);
        model.put("project", pMap);

        // project_scope_list — pre-rendered bullet list usable inside table cells
        Numberings.NumberingBuilder scopeBuilder = Numberings.ofBullet();
        scopeEntries.forEach(s -> {
            String item = "[" + s.getKind() + "] " + s.getValue();
            if (s.getNotes() != null && !s.getNotes().isBlank()) item += " (" + s.getNotes() + ")";
            scopeBuilder.addItem(item);
        });
        model.put("project_scope_list", scopeBuilder.create());
        model.put("projectScope", scopeList);
        model.put("projectScopeEmpty", scopeList.isEmpty());
        model.put("projectScopeNotEmpty", !scopeList.isEmpty());
        List<Map<String, Object>> scopeIn  = scopeList.stream().filter(s -> "IN".equals(s.get("inScope"))).toList();
        List<Map<String, Object>> scopeOut = scopeList.stream().filter(s -> "OUT".equals(s.get("inScope"))).toList();
        model.put("projectScopeIn",      scopeIn);
        model.put("projectScopeInEmpty", scopeIn.isEmpty());
        model.put("projectScopeInNotEmpty", !scopeIn.isEmpty());
        model.put("projectScopeOut",      scopeOut);
        model.put("projectScopeOutEmpty", scopeOut.isEmpty());
        model.put("projectScopeOutNotEmpty", !scopeOut.isEmpty());

        // team
        List<ProjectMember> members = projectMemberRepo.findByIdProjectId(project.getId());
        Map<Long, User> usersById = userRepo.findAllById(
            members.stream().map(m -> m.getId().getUserId()).toList()
        ).stream().collect(Collectors.toMap(User::getId, u -> u));
        List<Map<String, Object>> teamList = members.stream().map(m -> {
            User u = usersById.get(m.getId().getUserId());
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("userId", m.getId().getUserId());
            tm.put("role", m.getId().getRole() != null ? m.getId().getRole() : "");
            tm.put("displayName", u != null ? u.getDisplayName() : "");
            tm.put("email", u != null ? u.getEmail() : "");
            tm.put("addedAt", m.getAddedAt() != null ? m.getAddedAt().toString() : "");
            return tm;
        }).toList();
        model.put("team", teamList);
        model.put("teamEmpty", teamList.isEmpty());
        model.put("teamNotEmpty", !teamList.isEmpty());

        // team_list — pre-rendered bullet list usable inside table cells
        Numberings.NumberingBuilder teamListBuilder = Numberings.ofBullet();
        teamList.forEach(m -> teamListBuilder.addItem(
            m.get("displayName") + " (" + m.get("role") + "): " + m.get("email")
        ));
        model.put("team_list", teamListBuilder.create());

        // Detections available to this report — computed early since resolveFieldTemplate (used
        // by the "fields" block right below) needs them for {{?detections}} inside custom field
        // content, same as it already does for {{?findings}}.
        TreeSet<String> iterationLabels = findingIterationLabels(findings);
        List<Detection> detections = resolveReportDetections(project.getId(), iterationLabels);

        // fields (report-level custom fields — resolve template vars before stripping HTML)
        Map<String, Object> fieldsMap = new LinkedHashMap<>();
        customFields.forEach((slug, content) ->
            putField(fieldsMap, slug, renderFieldHtml(resolveFieldTemplate(content, org, project, findings, detections)))
        );
        model.put("fields", fieldsMap);

        // computed severity counts (additions beyond the JSON)
        Map<String, Long> bySeverity = findings.stream()
            .collect(Collectors.groupingBy(
                f -> f.getSeverity() != null ? f.getSeverity().toLowerCase() : "unknown",
                Collectors.counting()
            ));
        model.put("findingsCount", String.valueOf(findings.size()));
        for (String sev : List.of("critical", "high", "medium", "low", "info")) {
            String key = "findingsCount" + Character.toUpperCase(sev.charAt(0)) + sev.substring(1);
            model.put(key, String.valueOf(bySeverity.getOrDefault(sev, 0L)));
        }

        // Top-level chart images: {{@chartPie}} and {{@chartBar}} in the Word template.
        // Also available via {{chart:pie}} / {{chart:bar}} tokens inside field HTML.
        System.setProperty("java.awt.headless", "true");
        try {
            byte[] piePng = renderChartPng("pie", bySeverity);
            model.put("chartPie",
                com.deepoove.poi.data.Pictures.ofBytes(piePng, com.deepoove.poi.data.PictureType.PNG)
                    .size(300, 210).create());
            byte[] barPng = renderChartPng("bar", bySeverity);
            model.put("chartBar",
                com.deepoove.poi.data.Pictures.ofBytes(barPng, com.deepoove.poi.data.PictureType.PNG)
                    .size(300, 210).create());
        } catch (Exception ex) {
            log.warn("Server-side chart rendering failed — {{@chartPie}}/{{@chartBar}} will be absent: {}", ex.getMessage());
        }

        // findings
        // Resolved once per report: severity -> the literal text this template shows for that
        // priority (a custom label if the author configured one, else the P0-P4 default). The
        // color-replacement pass below (applyPriorityColors) must detect this same text, since
        // it's a post-processing scan over what actually got rendered here.
        Map<String, String> priorityLabels = resolvePriorityLabels(template);
        List<Map<String, Object>> findingsList = new ArrayList<>();
        for (Finding f : findings) {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("code", f.getCode() != null ? f.getCode() : "");
            fm.put("title", f.getTitle() != null ? f.getTitle() : "");
            String severityVal = f.getSeverity() != null
                ? priorityLabels.getOrDefault(f.getSeverity().toLowerCase(), PriorityLabels.forSeverity(f.getSeverity()))
                : "P?";
            fm.put("severity", severityVal);
            fm.put("status", f.getStatusId());

            // fields (finding custom fields, already keyed by slug in the jsonb column)
            Map<String, Object> fFields = new LinkedHashMap<>();
            com.martecyber.ares.findings.FindingFields.read(f)
                .forEach((slug, text) -> putField(fFields, slug, renderFieldHtml(text)));
            fm.put("fields", fFields);

            // defaultScore
            List<FindingScore> scores = findingScoreRepo.findByFindingId(f.getId());
            FindingScore defScore = scores.stream().filter(FindingScore::isDefault).findFirst().orElse(null);
            Map<String, Object> dsMap = new LinkedHashMap<>();
            if (defScore != null) {
                dsMap.put("type", scoreTypeNames.getOrDefault(defScore.getTypeId(), ""));
                dsMap.put("score", defScore.getScore());
                dsMap.put("vector", defScore.getVector() != null ? defScore.getVector() : "");
            }
            fm.put("defaultScore", dsMap);

            // scores
            List<Map<String, Object>> scoresList = scores.stream().map(s -> {
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("type", scoreTypeNames.getOrDefault(s.getTypeId(), ""));
                sm.put("score", s.getScore());
                sm.put("isDefault", s.isDefault());
                sm.put("vector", s.getVector() != null ? s.getVector() : "");
                return sm;
            }).toList();
            fm.put("scores", scoresList);
            fm.put("scoresEmpty", scoresList.isEmpty());
            fm.put("scoresNotEmpty", !scoresList.isEmpty());

            // references
            // references — full list + one pre-filtered list per catalog (referencesCwe, referencesCapec, …)
            List<Map<String, Object>> refsList = refEntryRepo.findByFindingId(f.getId()).stream().map(r -> {
                Map<String, Object> rm = new LinkedHashMap<>();
                ReferenceCatalog cat = catalogsById.get(r.getCatalogId());
                rm.put("catalog", cat != null ? cat.getCode() : "");
                rm.put("title", r.getTitle() != null ? r.getTitle() : "");
                rm.put("description", r.getDescription() != null ? r.getDescription() : "");
                return rm;
            }).toList();
            fm.put("references", refsList);
            fm.put("referencesEmpty", refsList.isEmpty());
            fm.put("referencesNotEmpty", !refsList.isEmpty());
            refsList.stream()
                .collect(Collectors.groupingBy(r -> (String) r.get("catalog")))
                .forEach((catalogCode, list) -> {
                    if (!catalogCode.isEmpty()) {
                        String key = "references" + catalogCode.charAt(0) + catalogCode.substring(1).toLowerCase();
                        fm.put(key, list);
                        fm.put(key + "Empty", list.isEmpty());
                        Numberings.NumberingBuilder b = Numberings.ofBullet();
                        list.forEach(r -> b.addItem(r.get("title") + " — " + r.get("description")));
                        fm.put(key + "_list", b.create());
                    }
                });

            // affections + affectedAssets (deduplicated) — sorted ASC by creation
            List<Affection> affections = affectionRepo.findByFindingIdWithAssets(f.getId())
                .stream()
                .sorted(Comparator.comparing(Affection::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
            Map<Long, Map<String, Object>> uniqueAffected = new LinkedHashMap<>();
            List<Map<String, Object>> affectionsList = affections.stream().map(a -> {
                Map<String, Object> am = new LinkedHashMap<>();
                am.put("code", a.getCode() != null ? a.getCode() : "");
                am.put("title", a.getTitle() != null ? a.getTitle() : "");
                putField(am, "description", renderFieldHtml(a.getDescription()));
                am.put("status", a.getStatus() != null ? a.getStatus() : "");

                List<AffectionAsset> affectsLinks = a.getAssetLinks().stream()
                    .filter(al -> "affects".equals(al.getRole())).toList();
                am.put("affectedCount", String.valueOf(affectsLinks.size()));
                am.put("stillAffectedCount", String.valueOf(affectsLinks.stream()
                    .filter(al -> !"resolved".equals(al.getStatus())).count()));
                am.put("affectsEmpty", affectsLinks.isEmpty());
                am.put("affectsNotEmpty", !affectsLinks.isEmpty());

                List<Map<String, Object>> affectsList = affectsLinks.stream().map(al -> {
                    Map<String, Object> m = buildAssetMap(al.getAsset(), al.getAssetId());
                    m.put("status", al.getStatus() != null ? al.getStatus() : "");
                    uniqueAffected.computeIfAbsent(al.getAssetId(),
                        id -> buildAssetMap(al.getAsset(), id));
                    return m;
                }).toList();
                am.put("affects", affectsList);

                // Pre-rendered bullet list for use as scalar inside table cells
                Numberings.NumberingBuilder affectsBuilder = Numberings.ofBullet();
                affectsList.forEach(asset -> affectsBuilder.addItem(
                    "[" + asset.get("type") + "] " + asset.get("identifier") +
                    " (" + asset.get("status") + ")"
                ));
                am.put("affects_list", affectsBuilder.create());

                List<Map<String, Object>> detectedAtList = a.getAssetLinks().stream()
                    .filter(al -> "detected_at".equals(al.getRole())).map(al -> {
                        Map<String, Object> m = buildAssetMap(al.getAsset(), al.getAssetId());
                        m.put("observedAt", al.getObservedAt() != null ? al.getObservedAt().toString() : "");
                        return m;
                    }).toList();
                am.put("detectedAt", detectedAtList);
                am.put("detectedAtEmpty", detectedAtList.isEmpty());
                am.put("detectedAtNotEmpty", !detectedAtList.isEmpty());

                Numberings.NumberingBuilder detectedAtBuilder = Numberings.ofBullet();
                detectedAtList.forEach(asset -> detectedAtBuilder.addItem(
                    "[" + asset.get("type") + "] " + asset.get("identifier")
                ));
                am.put("detectedAt_list", detectedAtBuilder.create());
                return am;
            }).toList();
            fm.put("affections", affectionsList);
            fm.put("affectionsCount", String.valueOf(affectionsList.size()));
            fm.put("affections.count", String.valueOf(affectionsList.size()));
            fm.put("affectionsEmpty", affectionsList.isEmpty());
            fm.put("affectionsNotEmpty", !affectionsList.isEmpty());
            fm.put("affectedAssets", new ArrayList<>(uniqueAffected.values()));
            fm.put("affectedAssetsCount", String.valueOf(uniqueAffected.size()));
            fm.put("affectedAssetsEmpty", uniqueAffected.isEmpty());
            fm.put("affectedAssetsNotEmpty", !uniqueAffected.isEmpty());

            // retest history — status changes (with notes) recorded since this finding was
            // linked into a RETEST project's scope; empty lists/strings for normal reports
            List<Map<String, Object>> retestHistory = buildRetestHistory(f, project);
            fm.put("retestHistory", retestHistory);
            fm.put("retestHistoryEmpty", retestHistory.isEmpty());
            fm.put("retestHistoryNotEmpty", !retestHistory.isEmpty());
            putField(fm, "retestNote", renderFieldHtml(buildRetestNote(retestHistory)));

            findingsList.add(fm);
        }
        model.put("findings", findingsList);
        model.put("findingsEmpty", findingsList.isEmpty());
        model.put("findingsNotEmpty", !findingsList.isEmpty());

        // detections (+ per-iteration breakdown, MONITOR projects) — scoped to the same
        // iteration labels as the findings above when present, else the whole project
        // (iterationLabels/detections were already resolved above, for resolveFieldTemplate).
        List<Map<String, Object>> detectionsList = buildDetectionsList(detections);
        model.put("detections", detectionsList);
        model.put("detectionsEmpty", detectionsList.isEmpty());
        model.put("detectionsNotEmpty", !detectionsList.isEmpty());
        Map<String, Long> bySeverityD = detections.stream()
            .collect(Collectors.groupingBy(
                d -> d.getSeverity() != null ? d.getSeverity().toLowerCase() : "unknown",
                Collectors.counting()
            ));
        model.put("detectionsCount", String.valueOf(detections.size()));
        for (String sev : List.of("critical", "high", "medium", "low", "info")) {
            String key = "detectionsCount" + Character.toUpperCase(sev.charAt(0)) + sev.substring(1);
            model.put(key, String.valueOf(bySeverityD.getOrDefault(sev, 0L)));
        }
        List<Map<String, Object>> detectionsByIteration = buildDetectionsByIteration(project.getId(), iterationLabels);
        model.put("detectionsByIteration", detectionsByIteration);
        model.put("detectionsByIterationEmpty", detectionsByIteration.isEmpty());
        model.put("detectionsByIterationNotEmpty", !detectionsByIteration.isEmpty());

        templateBytes = repairSplitTemplateTags(templateBytes);

        // LoopRowTableRenderPolicy: {{listName}} in a dedicated trigger row (removed after render),
        // the NEXT row is the template row duplicated per item using [[fieldName]] delimiters.
        LoopRowTableRenderPolicy loopRow = new LoopRowTableRenderPolicy("[", "]", true);
        Configure configure = Configure.builder()
            .bind("findings",       loopRow)
            .bind("team",           loopRow)
            .bind("projectScope",    loopRow)
            .bind("projectScopeIn",  loopRow)
            .bind("projectScopeOut", loopRow)
            .bind("scores",         loopRow)
            .bind("references",     loopRow)
            .bind("affections",     loopRow)
            .bind("affects",        loopRow)
            .bind("detectedAt",     loopRow)
            .bind("affectedAssets", loopRow)
            .bind("retestHistory", loopRow)
            .bind("detections",         loopRow)
            .bind("detectionsByIteration", loopRow)
            .build();

        try (InputStream tplStream = new ByteArrayInputStream(templateBytes);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFTemplate tpl = XWPFTemplate.compile(tplStream, configure).render(model);
            tpl.writeAndClose(out);
            byte[] rendered = out.toByteArray();
            if (template.getPriorityColors() != null && !template.getPriorityColors().isEmpty()) {
                rendered = applyPriorityColors(rendered, template, priorityLabels);
            }
            return rendered;
        }
    }

    /**
     * Resolves the display text for each of the 5 priority levels for this template: the
     * author's configured {@code label} if set, otherwise the P0-P4 default.
     */
    private Map<String, String> resolvePriorityLabels(ReportTemplate template) {
        Map<String, PriorityColor> colors = template.getPriorityColors();
        Map<String, String> labels = new LinkedHashMap<>();
        for (String sev : new String[]{"critical", "high", "medium", "low", "info"}) {
            PriorityColor pc = colors != null ? colors.get(sev) : null;
            labels.put(sev, (pc != null && pc.label() != null && !pc.label().isBlank())
                ? pc.label() : PriorityLabels.forSeverity(sev));
        }
        return labels;
    }

    /**
     * Replaces the magic placeholder color in the rendered document with
     * per-priority-level colors. Scans body elements sequentially, tracking the
     * current priority from rendered priority-label text runs — matching against
     * {@code priorityLabels} (the resolved, possibly-custom label text) since that's
     * what actually got rendered by the merge pass, not the raw severity key.
     */
    private byte[] applyPriorityColors(byte[] docxBytes, ReportTemplate template,
                                        Map<String, String> priorityLabels) throws Exception {
        String magic = template.getMagicColor().toUpperCase();
        Map<String, PriorityColor> colors = template.getPriorityColors();
        // severity -> UPPERCASED label text to look for in the rendered document
        Map<String, String> matchLabels = new LinkedHashMap<>();
        for (String sev : colors.keySet()) {
            String label = priorityLabels.get(sev);
            if (label != null && !label.isBlank()) matchLabels.put(sev, label.toUpperCase());
        }

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docxBytes))) {
            final String[] currentSeverity = {null};

            java.util.function.Consumer<XWPFParagraph> processPara = para -> {
                // Detect severity from paragraph text. Word splits a coloured
                // word into multiple runs and may inject non-breaking spaces,
                // so we normalize whitespace and compare both as exact and
                // as a single-token presence (so "Severidad: CRITICAL" works).
                String text = para.getText();
                if (text != null) {
                    String norm = text.replace(' ', ' ')
                                      .replaceAll("\\s+", " ").trim().toUpperCase();
                    if (!norm.isEmpty()) {
                        for (var entry : matchLabels.entrySet()) {
                            String labelUpper = entry.getValue();
                            if (norm.equals(labelUpper)
                                || norm.endsWith(" " + labelUpper)
                                || norm.startsWith(labelUpper + " ")
                                || norm.contains(" " + labelUpper + " ")) {
                                currentSeverity[0] = entry.getKey();
                                break;
                            }
                        }
                    }
                }
                if (currentSeverity[0] == null) return;
                PriorityColor sc = colors.get(currentSeverity[0]);
                if (sc == null) return;
                for (XWPFRun run : para.getRuns()) {
                    replaceRunColor(run, magic, sc);
                }
            };

            applyToBodyElements(doc.getBodyElements(), processPara, magic, colors, matchLabels, currentSeverity);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.write(out);
            return out.toByteArray();
        }
    }

    private void applyToBodyElements(List<IBodyElement> elements,
                                      java.util.function.Consumer<XWPFParagraph> processPara,
                                      String magic, Map<String, PriorityColor> colors,
                                      Map<String, String> matchLabels, String[] currentSeverity) {
        for (IBodyElement el : elements) {
            if (el instanceof XWPFParagraph) {
                processPara.accept((XWPFParagraph) el);
            } else if (el instanceof XWPFTable) {
                for (XWPFTableRow row : ((XWPFTable) el).getRows()) {
                    // Pre-scan the entire row to find the priority token before processing
                    // individual cells. Without this, cells that appear to the LEFT of the
                    // priority column get the previous row's color (because currentSeverity[0]
                    // only updates when the priority cell is processed left-to-right).
                    String rowSeverity = detectSeverityInRow(row, matchLabels);
                    if (rowSeverity != null) currentSeverity[0] = rowSeverity;
                    for (XWPFTableCell cell : row.getTableCells()) {
                        applyToBodyElements(cell.getBodyElements(), processPara, magic, colors, matchLabels, currentSeverity);
                        if (currentSeverity[0] != null) {
                            PriorityColor sc = colors.get(currentSeverity[0]);
                            if (sc != null) replaceCellFill(cell, magic, sc);
                        }
                    }
                }
            }
        }
    }

    private String detectSeverityInRow(XWPFTableRow row, Map<String, String> matchLabels) {
        for (XWPFTableCell cell : row.getTableCells()) {
            for (XWPFParagraph para : cell.getParagraphs()) {
                String text = para.getText();
                if (text == null) continue;
                String norm = text.replace(' ', ' ').replaceAll("\\s+", " ").trim().toUpperCase();
                if (norm.isEmpty()) continue;
                for (var entry : matchLabels.entrySet()) {
                    String labelUpper = entry.getValue();
                    if (norm.equals(labelUpper)
                            || norm.endsWith(" " + labelUpper)
                            || norm.startsWith(labelUpper + " ")
                            || norm.contains(" " + labelUpper + " ")) {
                        return entry.getKey();
                    }
                }
            }
        }
        return null;
    }

    private void replaceRunColor(XWPFRun run, String magic, PriorityColor sc) {
        // Text color
        if (magic.equalsIgnoreCase(run.getColor())) {
            run.setColor(sc.bgColor().toUpperCase());
        }
        org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr rpr = run.getCTR().getRPr();
        if (rpr == null) return;

        // Underline color (via CTRPr array API)
        if (rpr.sizeOfUArray() > 0) {
            for (org.openxmlformats.schemas.wordprocessingml.x2006.main.CTUnderline u : rpr.getUArray()) {
                if (u.isSetColor()) {
                    // Same XMLBeans issue: getColor() → Object; use xgetColor().getStringValue()
                    org.openxmlformats.schemas.wordprocessingml.x2006.main.STHexColor xColor = u.xgetColor();
                    if (xColor != null && magic.equalsIgnoreCase(xColor.getStringValue())) {
                        xColor.setStringValue(sc.bgColor().toUpperCase());
                    }
                }
            }
        }

        // Character-level shading: Word's "Borders and Shading > Fill" applied
        // to selected text emits <w:shd w:fill="..."/> inside <w:rPr>. Without
        // this branch, a template that highlights the severity word itself
        // (not the surrounding cell) keeps the magic placeholder visible.
        // XMLBeans models this as a 0/1-sized array on CTRPr.
        if (rpr.sizeOfShdArray() > 0) {
            for (org.openxmlformats.schemas.wordprocessingml.x2006.main.CTShd shd : rpr.getShdArray()) {
                replaceShdFillIfMagic(shd, magic, sc.bgColor());
            }
        }

        // Run highlight (<w:highlight>): limited to ~16 named colors so we'd
        // have to map magenta→a name to support this. Templates almost always
        // use shading instead, so we skip highlight for now.
    }

    private void replaceCellFill(XWPFTableCell cell, String magic, PriorityColor sc) {
        // Word emits cell shading in three different places depending on how
        // the author applied it in the editor. We scan all three and replace
        // any <w:shd> whose w:fill equals the magic placeholder. This is more
        // reliable than POI's cell.getColor()/setColor(), which only sees the
        // tcPr variant and silently misses the others.
        boolean changed = false;

        // 1. <w:tcPr><w:shd> — table-cell-level shading (single, not array)
        org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr tcPr =
            cell.getCTTc().getTcPr();
        if (tcPr != null && tcPr.isSetShd()) {
            if (replaceShdFillIfMagic(tcPr.getShd(), magic, sc.bgColor())) changed = true;
        }

        // 2/3. <w:pPr><w:shd> and <w:rPr><w:shd> inside each paragraph/run of
        // the cell. Paragraph- and run-level shading are common when the
        // template author selected the value text and applied "Borders and
        // Shading > Fill" rather than formatting the cell itself.
        for (XWPFParagraph p : cell.getParagraphs()) {
            if (p.getCTP().isSetPPr()) {
                org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr pPr =
                    p.getCTP().getPPr();
                if (pPr.isSetShd()) {
                    if (replaceShdFillIfMagic(pPr.getShd(), magic, sc.bgColor())) changed = true;
                }
            }
            for (XWPFRun run : p.getRuns()) {
                org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr rpr =
                    run.getCTR().getRPr();
                if (rpr != null) {
                    for (org.openxmlformats.schemas.wordprocessingml.x2006.main.CTShd shd
                            : rpr.getShdArray()) {
                        if (replaceShdFillIfMagic(shd, magic, sc.bgColor())) changed = true;
                    }
                }
            }
        }

        // Only override the cell's text color when we actually changed a fill,
        // so we don't repaint runs in cells that were never magenta.
        if (changed) {
            for (XWPFParagraph p : cell.getParagraphs()) {
                for (XWPFRun run : p.getRuns()) {
                    run.setColor(sc.textColor().toUpperCase());
                }
            }
        }
    }

    private boolean replaceShdFillIfMagic(
            org.openxmlformats.schemas.wordprocessingml.x2006.main.CTShd shd,
            String magic, String newFillHex) {
        if (!shd.isSetFill()) return false;
        // getFill() returns Object (byte[] for hex binary in XMLBeans) — toString() is garbage.
        // Use xgetFill().getStringValue() to get the actual hex string from the XML attribute.
        org.openxmlformats.schemas.wordprocessingml.x2006.main.STHexColor xFill = shd.xgetFill();
        if (xFill == null) return false;
        String current = xFill.getStringValue();
        if (current == null || !magic.equalsIgnoreCase(current)) return false;
        xFill.setStringValue(newFillHex.toUpperCase());
        return true;
    }

    private Set<String> extractTemplateVars(byte[] docxBytes) throws Exception {
        Set<String> vars = new LinkedHashSet<>();
        Pattern p = Pattern.compile("\\{\\{([^}#/][^}]*)\\}\\}");
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docxBytes))) {
            StringBuilder text = new StringBuilder();
            for (XWPFParagraph para : doc.getParagraphs()) text.append(para.getText()).append(" ");
            for (XWPFTable table : doc.getTables())
                for (XWPFTableRow row : table.getRows())
                    for (XWPFTableCell cell : row.getTableCells())
                        for (XWPFParagraph para : cell.getParagraphs())
                            text.append(para.getText()).append(" ");
            Matcher m = p.matcher(text);
            while (m.find()) vars.add(m.group(1).trim());
        }
        return vars;
    }

    private void putIfMapped(Map<String, Object> model, Map<String, String> mapping, String field, Object value) {
        String varName = mapping.get(field);
        if (varName != null) model.put(varName, value);
    }

    private ReportTemplate resolveTemplate(Long templateId, Project project) {
        if (templateId != null)
            return templateRepo.findById(templateId).orElseThrow(() -> NotFoundException.of("report_template", templateId));
        if (project.getTypeId() != null) {
            List<ReportTemplate> candidates = templateRepo.findForProjectType(project.getTypeId());
            if (!candidates.isEmpty()) return candidates.get(0);
        }
        return templateRepo.findByIsActiveTrueOrderByNameAsc().stream()
            .filter(ReportTemplate::isGeneric).findFirst()
            .orElseThrow(() -> new IllegalStateException("No report template available. Upload a template first."));
    }

    /**
     * Resolves poi-tl-compatible template tokens in a custom field's HTML content
     * before it is stripped and embedded in the Word document.
     *
     * Supported tokens (same names as the poi-tl Word context):
     *  - {{project.name}}, {{project.code}}, {{organization.name}}
     *  - {{findingsCount}}, {{findingsCountCritical/High/Medium/Low/Info}}
     *  - {{?findings}}...{{/findings}} loop with {{code}}, {{title}}, {{severity}}, {{status}}
     */
    private String resolveFieldTemplate(String content, Organization org, Project project,
                                        List<Finding> findings, List<Detection> detections) {
        if (content == null || content.isBlank()) return "";

        Map<Long, String> statusNames = findingStatusRepo.findAll().stream()
            .collect(Collectors.toMap(FindingStatus::getId, fs -> fs.getName() != null ? fs.getName() : ""));

        // Count by severity
        Map<String, Long> bySev = findings.stream().collect(
            Collectors.groupingBy(
                f -> f.getSeverity() != null ? f.getSeverity().toLowerCase() : "unknown",
                Collectors.counting()));
        Map<String, Long> bySevD = detections.stream().collect(
            Collectors.groupingBy(
                d -> d.getSeverity() != null ? d.getSeverity().toLowerCase() : "unknown",
                Collectors.counting()));

        String s = content;

        // Chart tokens: render to PNG via Java2D and embed as data URI so that
        // renderFieldHtml() picks them up as normal <img> elements.
        for (String token : new String[]{"{{chart:pie}}", "{{chart:bar}}"}) {
            if (!s.contains(token)) continue;
            String chartType = token.contains("pie") ? "pie" : "bar";
            try {
                byte[] png = renderChartPng(chartType, bySev);
                String dataUri = "data:image/png;base64,"
                    + java.util.Base64.getEncoder().encodeToString(png);
                s = s.replace(token, "<img src=\"" + dataUri + "\">");
            } catch (Exception ex) {
                log.warn("Chart token {} rendering failed: {}", token, ex.getMessage());
                s = s.replace(token, "");
            }
        }

        // Scalar replacements
        s = s.replace("{{project.name}}",          project.getName() != null ? project.getName() : "");
        s = s.replace("{{project.code}}",          project.getCode() != null ? project.getCode() : "");
        s = s.replace("{{organization.name}}",     org != null && org.getName() != null ? org.getName() : "");
        s = s.replace("{{findingsCount}}",          String.valueOf(findings.size()));
        s = s.replace("{{findingsCountCritical}}",  String.valueOf(bySev.getOrDefault("critical", 0L)));
        s = s.replace("{{findingsCountHigh}}",      String.valueOf(bySev.getOrDefault("high", 0L)));
        s = s.replace("{{findingsCountMedium}}",    String.valueOf(bySev.getOrDefault("medium", 0L)));
        s = s.replace("{{findingsCountLow}}",       String.valueOf(bySev.getOrDefault("low", 0L)));
        s = s.replace("{{findingsCountInfo}}",      String.valueOf(bySev.getOrDefault("info", 0L)));
        s = s.replace("{{detectionsCount}}",         String.valueOf(detections.size()));
        s = s.replace("{{detectionsCountCritical}}", String.valueOf(bySevD.getOrDefault("critical", 0L)));
        s = s.replace("{{detectionsCountHigh}}",     String.valueOf(bySevD.getOrDefault("high", 0L)));
        s = s.replace("{{detectionsCountMedium}}",   String.valueOf(bySevD.getOrDefault("medium", 0L)));
        s = s.replace("{{detectionsCountLow}}",      String.valueOf(bySevD.getOrDefault("low", 0L)));
        s = s.replace("{{detectionsCountInfo}}",     String.valueOf(bySevD.getOrDefault("info", 0L)));

        // Loop: {{?findings}}...{{/findings}}
        java.util.regex.Pattern loopPat = java.util.regex.Pattern.compile(
            "\\{\\{\\?findings\\}\\}([\\s\\S]*?)\\{\\{/findings\\}\\}");
        java.util.regex.Matcher matcher = loopPat.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String tpl = matcher.group(1);
            StringBuilder out = new StringBuilder();
            for (Finding f : findings) {
                out.append(tpl
                    .replace("{{code}}",     f.getCode()     != null ? f.getCode()     : "—")
                    .replace("{{title}}",    f.getTitle()    != null ? f.getTitle()    : "")
                    .replace("{{severity}}", f.getSeverity() != null ? f.getSeverity().toUpperCase() : "")
                    .replace("{{status}}",   statusNames.getOrDefault(f.getStatusId(), ""))
                    .replace("{{retestNote}}", buildRetestNote(buildRetestHistory(f, project)))
                );
            }
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(out.toString()));
        }
        matcher.appendTail(sb);
        s = sb.toString();

        // Loop: {{?detections}}...{{/detections}}
        java.util.regex.Pattern detLoopPat = java.util.regex.Pattern.compile(
            "\\{\\{\\?detections\\}\\}([\\s\\S]*?)\\{\\{/detections\\}\\}");
        java.util.regex.Matcher detMatcher = detLoopPat.matcher(s);
        StringBuffer detSb = new StringBuffer();
        while (detMatcher.find()) {
            String tpl = detMatcher.group(1);
            StringBuilder out = new StringBuilder();
            for (Detection d : detections) {
                out.append(tpl
                    .replace("{{id}}",             String.valueOf(d.getId()))
                    .replace("{{title}}",          d.getTitle()      != null ? d.getTitle()      : "")
                    .replace("{{severity}}",       d.getSeverity()   != null ? d.getSeverity().toUpperCase() : "")
                    .replace("{{status}}",         d.getStatus()     != null ? d.getStatus()      : "")
                    .replace("{{sourceType}}",     d.getSourceType() != null ? d.getSourceType()  : "")
                    .replace("{{occurrenceCount}}", String.valueOf(d.getOccurrenceCount()))
                );
            }
            detMatcher.appendReplacement(detSb, java.util.regex.Matcher.quoteReplacement(out.toString()));
        }
        detMatcher.appendTail(detSb);
        return detSb.toString();
    }

    // IndentedCodeBlock intentionally excluded — this app never intentionally produces
    // a 4-space-indented code block (the only way to get a real code block through the
    // editor is a fenced ``` block), so any occurrence is unintentional legacy leading
    // whitespace. Mirrors the frontend's md.disable(['code']) fix in
    // ares-ui/src/utils/markdown.ts — see [[markdown_rich_text_migration]]. Paragraph
    // is not itself a valid argument to enabledBlockTypes() (it's always enabled,
    // not one of the optional/toggleable block types) — the valid options are exactly
    // the other 7 listed here.
    private static final Parser MARKDOWN_PARSER = Parser.builder()
        .enabledBlockTypes(Set.of(
            BlockQuote.class, Heading.class, FencedCodeBlock.class,
            HtmlBlock.class, ThematicBreak.class, ListBlock.class
        ))
        .build();
    private static final HtmlRenderer MARKDOWN_RENDERER = HtmlRenderer.builder().build();

    /**
     * Converts a stored Markdown rich-text field to HTML. Raw HTML embedded in the
     * source (e.g. the {@code <img>} tags {@link #resolveFieldTemplate} injects for
     * {@code {{chart:pie}}}/{{chart:bar}}} tokens) passes through untouched — this
     * output is only ever consumed by the Jsoup/poi-tl pipeline below to build a
     * Word document, never rendered in a browser, so there's no XSS surface here.
     */
    private static String markdownToHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) return "";
        Node document = MARKDOWN_PARSER.parse(markdown);
        return MARKDOWN_RENDERER.render(document);
    }

    /** Strips HTML tags and decodes entities, preserving line breaks. */
    private static String stripHtml(String html) {
        if (html == null || html.isBlank()) return "";
        Document doc = Jsoup.parse(html);
        doc.select("br").before("\\n");
        doc.select("p, div, li, tr").after("\\n");
        return doc.text().replace("\\n", "\n").replaceAll("\n{3,}", "\n\n").trim();
    }

    /**
     * Parsed representation of a rich-text field.
     *
     * <ul>
     *   <li>{@code text} — HTML-stripped plain text, structure discarded; always safe
     *       to use in a poi-tl text tag {@code {{field}}} (e.g. inside a table cell).</li>
     *   <li>{@code docData} — {@code DocumentRenderData} that mirrors the field's
     *       Markdown structure (headings, bold/italic, lists, blockquotes, code
     *       blocks, images in their original position) as real Word formatting, or
     *       {@code null} when the field is empty. Use it with the poi-tl block tag
     *       {@code {{@field_doc}}} — the {@code _doc} suffix keeps the key separate
     *       from the plain-text key so existing templates using {@code {{field}}}
     *       keep getting flat text.</li>
     * </ul>
     */
    // Package-private (not private): buildStructuredDocument/renderFieldHtml/FieldContent are
    // exercised directly by ReportGenerationServiceMarkdownTest, since this is otherwise-untested
    // recursive DOM-walking logic where manual end-to-end docx inspection alone is too slow to
    // iterate on.
    record FieldContent(String text, com.deepoove.poi.data.DocumentRenderData docData) {}

    /** Decoded image ready for poi-tl: bytes + the OOXML picture type inferred from the MIME type. */
    private record ImageData(byte[] bytes, com.deepoove.poi.data.PictureType type) {}

    /** Accumulated inline character formatting while walking a paragraph's inline children. */
    private record InlineState(boolean bold, boolean italic, boolean mono, String linkHref) {
        static final InlineState PLAIN = new InlineState(false, false, false, null);
        InlineState withBold()   { return new InlineState(true, italic, mono, linkHref); }
        InlineState withItalic() { return new InlineState(bold, true, mono, linkHref); }
        InlineState withMono()   { return new InlineState(bold, italic, true, linkHref); }
        InlineState withLink(String href) { return new InlineState(bold, italic, mono, href); }
    }

    /**
     * Parses a rich-text field (stored as Markdown, converted to HTML via
     * {@link #markdownToHtml} first) into a {@link FieldContent}: {@code text} is
     * the existing flattened-to-plain-string behavior (unchanged), {@code docData}
     * is a real structured {@code DocumentRenderData} built by walking the HTML DOM
     * — see {@link #buildStructuredDocument}.
     */
    static FieldContent renderFieldHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) return new FieldContent("", null);
        String html = markdownToHtml(markdown);
        String text = stripHtml(html);
        com.deepoove.poi.data.DocumentRenderData docData = buildStructuredDocument(Jsoup.parse(html).body());
        return new FieldContent(text, docData);
    }

    /**
     * Walks a parsed HTML body in document order and builds a structured
     * {@code DocumentRenderData} — the {@code _doc} counterpart to {@link #stripHtml}'s
     * flattened text, used so generated Word content follows the uploaded template's
     * own styles instead of dumping everything as one plain paragraph:
     * <ul>
     *   <li>{@code h1}-{@code h6} → paragraph styled {@code Heading1}-{@code Heading6}
     *       (Word built-in styles present in every template by default — if the
     *       template has customized them, generated headings inherit that styling).</li>
     *   <li>{@code ul}/{@code ol} → real Word numbering ({@link com.deepoove.poi.data.Numberings}),
     *       one list item per {@code li}, inline formatting preserved inside each item.</li>
     *   <li>{@code blockquote} → paragraph styled {@code Quote} (also a Word built-in).</li>
     *   <li>{@code pre}/{@code code} (fenced block) → one paragraph per line, monospace
     *       font + shading — Word has no universal built-in "Code" style to reuse here.</li>
     *   <li>{@code p} and other block containers → inline runs (bold/italic/inline-code/
     *       links) built from {@code strong}/{@code em}/{@code code}/{@code a}, with
     *       {@code img} children inserted at their real position in the flow.</li>
     * </ul>
     * Returns {@code null} when nothing renderable was found (empty field) — poi-tl
     * handles a null-valued {@code {{@field_doc}}} as empty; handing it an empty
     * {@code DocumentRenderData} has previously caused rendering issues.
     */
    private static com.deepoove.poi.data.DocumentRenderData buildStructuredDocument(Element body) {
        var docBuilder = com.deepoove.poi.data.Documents.of();
        int[] added = {0};
        for (org.jsoup.nodes.Node child : body.childNodes()) {
            if (child instanceof Element el) appendBlock(docBuilder, el, added);
        }
        return added[0] > 0 ? docBuilder.create() : null;
    }

    private static void appendBlock(com.deepoove.poi.data.Documents.DocumentBuilder docBuilder,
                                     Element el, int[] added) {
        switch (el.tagName()) {
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                int level = Math.min(Character.getNumericValue(el.tagName().charAt(1)), 6);
                var p = com.deepoove.poi.data.Paragraphs.of().styleId("Heading" + level);
                boolean[] wrote = {false};
                for (var c : el.childNodes()) walkInline(c, InlineState.PLAIN, p, wrote);
                if (wrote[0]) { docBuilder.addParagraph(p.create()); added[0]++; }
            }
            case "blockquote" -> {
                // commonmark wraps blockquote content in <p> — style each inner
                // paragraph as Quote; a blockquote with bare inline content (no <p>
                // wrapper) is styled the same way as a single paragraph.
                var innerParas = el.children().isEmpty() ? java.util.List.of(el) : el.children();
                for (Element inner : innerParas) {
                    var p = com.deepoove.poi.data.Paragraphs.of().styleId("Quote");
                    boolean[] wrote = {false};
                    for (var c : inner.childNodes()) walkInline(c, InlineState.PLAIN, p, wrote);
                    if (wrote[0]) { docBuilder.addParagraph(p.create()); added[0]++; }
                }
            }
            case "ul", "ol" -> appendList(docBuilder, el,
                "ol".equals(el.tagName()) ? com.deepoove.poi.data.Numberings.ofDecimal()
                                           : com.deepoove.poi.data.Numberings.ofBullet(),
                added);
            case "pre" -> {
                Element codeEl = el.selectFirst("code");
                String code = codeEl != null ? codeEl.text() : el.text();
                for (String line : code.split("\n", -1)) {
                    var p = com.deepoove.poi.data.Paragraphs.of()
                        .addText(com.deepoove.poi.data.Texts.of(line.isBlank() ? " " : line)
                            .fontFamily("Consolas").create())
                        .bgColor("F2F2F2");
                    docBuilder.addParagraph(p.create());
                    added[0]++;
                }
            }
            case "hr" -> { /* thematic break — no reliable cross-template visual, skipped */ }
            default -> {
                // "p" and anything else (e.g. a raw HtmlBlock wrapper) — one paragraph
                // of inline runs, images included at their real position.
                var p = com.deepoove.poi.data.Paragraphs.of();
                boolean[] wrote = {false};
                for (var c : el.childNodes()) walkInline(c, InlineState.PLAIN, p, wrote);
                if (wrote[0]) { docBuilder.addParagraph(p.create()); added[0]++; }
            }
        }
    }

    private static void appendList(com.deepoove.poi.data.Documents.DocumentBuilder docBuilder,
                                    Element listEl, com.deepoove.poi.data.Numberings.NumberingBuilder numBuilder,
                                    int[] added) {
        boolean any = false;
        for (Element li : listEl.children()) {
            if (!"li".equals(li.tagName())) continue;
            var p = com.deepoove.poi.data.Paragraphs.of();
            boolean[] wrote = {false};
            for (var c : li.childNodes()) {
                // Nested ul/ol inside a list item: not flattened (would read as
                // confusing run-on text) — skipped for this first pass.
                if (c instanceof Element el && ("ul".equals(el.tagName()) || "ol".equals(el.tagName()))) continue;
                walkInline(c, InlineState.PLAIN, p, wrote);
            }
            if (wrote[0]) { numBuilder.addItem(p.create()); any = true; }
        }
        if (any) { docBuilder.addNumbering(numBuilder.create()); added[0]++; }
    }

    /** Recursively walks inline content (text + strong/em/code/a/img), appending
     *  TextRenderData runs and PictureRenderData onto {@code p} in document order. */
    private static void walkInline(org.jsoup.nodes.Node node, InlineState state,
                                    com.deepoove.poi.data.Paragraphs.ParagraphBuilder p, boolean[] wrote) {
        if (node instanceof org.jsoup.nodes.TextNode tn) {
            String text = tn.text();
            if (text.isBlank()) return;
            var b = com.deepoove.poi.data.Texts.of(text);
            if (state.bold())   b.bold();
            if (state.italic()) b.italic();
            if (state.mono())   b.fontFamily("Consolas");
            if (state.linkHref() != null) b.link(state.linkHref());
            p.addText(b.create());
            wrote[0] = true;
            return;
        }
        if (!(node instanceof Element el)) return;
        switch (el.tagName()) {
            case "img" -> {
                ImageData imgData = decodeImage(el.attr("src"));
                com.deepoove.poi.data.PictureRenderData pic = imgData == null ? null : buildPicture(imgData);
                if (pic == null) return;
                p.addPicture(pic);
                wrote[0] = true;
            }
            case "strong", "b" -> { for (var c : el.childNodes()) walkInline(c, state.withBold(), p, wrote); }
            case "em", "i"     -> { for (var c : el.childNodes()) walkInline(c, state.withItalic(), p, wrote); }
            case "code"        -> { for (var c : el.childNodes()) walkInline(c, state.withMono(), p, wrote); }
            case "a"           -> { for (var c : el.childNodes()) walkInline(c, state.withLink(el.attr("href")), p, wrote); }
            case "br"          -> { /* poi-tl TextRenderData has no explicit line-break primitive */ }
            default            -> { for (var c : el.childNodes()) walkInline(c, state, p, wrote); }
        }
    }

    /** Builds a page-safe, correctly-sized/-typed poi-tl picture from decoded image bytes.
     *  Natural pixel dimensions (via ImageIO) are converted to points (poi-tl/XWPFRun
     *  treat width/height as points, screen pixels assumed 96 DPI) and capped to 420pt
     *  (≈ A4 usable width with standard margins). Without size + type, poi-tl inserts
     *  0×0 or incorrectly typed images that Word cannot render. */
    private static com.deepoove.poi.data.PictureRenderData buildPicture(ImageData imgData) {
        int[] size = naturalImageSize(imgData.bytes());
        int wPt = Math.round(size[0] * 72f / 96f);
        int hPt = Math.round(size[1] * 72f / 96f);
        final int maxPt = 420;
        if (wPt > maxPt) {
            hPt = Math.round((float) hPt * maxPt / wPt);
            wPt = maxPt;
        }
        if (wPt <= 0) wPt = 240;
        if (hPt <= 0) hPt = 180;
        return com.deepoove.poi.data.Pictures.ofBytes(imgData.bytes(), imgData.type()).size(wPt, hPt).create();
    }

    /** Puts a FieldContent into a map: text under {@code key}, structured DocumentRenderData
     *  (or nothing, when the field is empty) under {@code key_doc}. */
    private static void putField(Map<String, Object> map, String key, FieldContent fc) {
        map.put(key, fc.text());
        if (fc.docData() != null) map.put(key + "_doc", fc.docData());
    }

    /**
     * Decodes an {@code <img src="...">} attribute into bytes + picture type.
     * Supports {@code data:image/...;base64,...} (Quill's default) and
     * {@code http(s)://...} remote URLs. Returns null when the source cannot
     * be resolved — the image is silently skipped.
     */
    private static ImageData decodeImage(String src) {
        if (src == null || src.isBlank()) return null;
        com.deepoove.poi.data.PictureType pType = com.deepoove.poi.data.PictureType.PNG;
        byte[] bytes = null;
        if (src.startsWith("data:")) {
            int sc = src.indexOf(';');
            if (sc > 5) pType = mimeToPoiType(src.substring(5, sc).toLowerCase());
            int marker = src.indexOf("base64,");
            if (marker < 0) return null;
            try {
                // getMimeDecoder() tolerates line-breaks and whitespace that Quill / PostgreSQL
                // TEXT columns may inject into large base64 blobs; getDecoder() would throw.
                bytes = java.util.Base64.getMimeDecoder().decode(src.substring(marker + "base64,".length()));
            } catch (IllegalArgumentException e) {
                log.warn("Base64 decode failed for embedded image (len={}): {}", src.length(), e.getMessage());
                return null;
            }
        } else if (src.startsWith("http://") || src.startsWith("https://")) {
            String lower = src.toLowerCase();
            if (lower.contains(".jpg") || lower.contains(".jpeg")) pType = com.deepoove.poi.data.PictureType.JPEG;
            else if (lower.contains(".gif")) pType = com.deepoove.poi.data.PictureType.GIF;
            try (java.io.InputStream in = java.net.URI.create(src).toURL().openStream();
                 java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                in.transferTo(out);
                bytes = out.toByteArray();
            } catch (Exception e) {
                return null;
            }
        }
        return bytes == null ? null : new ImageData(bytes, pType);
    }

    private static com.deepoove.poi.data.PictureType mimeToPoiType(String mime) {
        return switch (mime) {
            case "image/jpeg", "image/jpg" -> com.deepoove.poi.data.PictureType.JPEG;
            case "image/gif"               -> com.deepoove.poi.data.PictureType.GIF;
            case "image/bmp"               -> com.deepoove.poi.data.PictureType.BMP;
            case "image/tiff"              -> com.deepoove.poi.data.PictureType.TIFF;
            case "image/emf"               -> com.deepoove.poi.data.PictureType.EMF;
            case "image/wmf"               -> com.deepoove.poi.data.PictureType.WMF;
            default                        -> com.deepoove.poi.data.PictureType.PNG;
        };
    }

    /** Returns the natural pixel dimensions of an image, falling back to 320×240 if unreadable. */
    private static int[] naturalImageSize(byte[] bytes) {
        try (java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(bytes)) {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(bais);
            if (img != null) return new int[]{img.getWidth(), img.getHeight()};
        } catch (Exception ignored) {}
        return new int[]{320, 240};
    }

    /** Converts snake_case to camelCase. executive_summary → executiveSummary */
    static String toCamelCase(String snake) {
        if (snake == null) return "";
        String[] parts = snake.split("_");
        StringBuilder sb = new StringBuilder(parts[0].toLowerCase());
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty())
                sb.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1).toLowerCase());
        }
        return sb.toString();
    }

    private boolean isAssessLineage(Long typeId, Set<Long> visited) {
        if (!visited.add(typeId)) return false;
        ProjectType t = engTypeRepo.findById(typeId).orElse(null);
        if (t == null) return false;
        if ("ASSESS".equals(t.getCode())) return true;
        if (t.getSupertypeId() != null) return isAssessLineage(t.getSupertypeId(), visited);
        return false;
    }

    // ── Server-side chart rendering ────────────────────────────────────────────

    // Mirrors frontend SEVERITY_ORDER + PLATFORM_SEVERITY_COLORS from templateVars.ts
    private static final String[] CHART_SEVERITIES = {"critical", "high", "medium", "low", "info"};
    private static final java.awt.Color[] CHART_COLORS = {
        new java.awt.Color(0xef, 0x44, 0x44),  // #ef4444  critical
        new java.awt.Color(0xf9, 0x73, 0x16),  // #f97316  high
        new java.awt.Color(0xca, 0x8a, 0x04),  // #ca8a04  medium
        new java.awt.Color(0x16, 0xa3, 0x4a),  // #16a34a  low
        new java.awt.Color(0x3b, 0x82, 0xf6),  // #3b82f6  info
    };

    /**
     * Renders a "pie" (doughnut) or "bar" severity chart as a PNG using Java2D.
     * Only severities with count > 0 are shown, matching the frontend filter.
     */
    private static byte[] renderChartPng(String type, Map<String, Long> bySev) throws Exception {
        java.util.List<Integer> active = new java.util.ArrayList<>();
        for (int i = 0; i < CHART_SEVERITIES.length; i++) {
            if (bySev.getOrDefault(CHART_SEVERITIES[i], 0L) > 0) active.add(i);
        }
        final int W = 400, H = 280;
        java.awt.image.BufferedImage img =
            new java.awt.image.BufferedImage(W, H, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                           java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                           java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                           java.awt.RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, W, H);

        if (active.isEmpty()) {
            g.setColor(new java.awt.Color(0x9c, 0xa3, 0xaf));
            g.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 13));
            g.drawString("No findings", W / 2 - 40, H / 2 + 5);
        } else if ("pie".equals(type)) {
            renderDoughnut(g, active, bySev, W, H);
        } else {
            renderBars(g, active, bySev, W, H);
        }
        g.dispose();
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "PNG", baos);
        return baos.toByteArray();
    }

    private static void renderDoughnut(java.awt.Graphics2D g, java.util.List<Integer> active,
                                        Map<String, Long> bySev, int W, int H) {
        long total = active.stream().mapToLong(i -> bySev.getOrDefault(CHART_SEVERITIES[i], 0L)).sum();
        final int diam = 200, ox = 20, cy = H / 2;
        final int centerX = ox + diam / 2;

        // Arcs — clockwise from 12 o'clock (startAngle=90, negative sweep = CW in Java2D)
        double startAngle = 90.0;
        for (int i = 0; i < active.size(); i++) {
            long count = bySev.getOrDefault(CHART_SEVERITIES[active.get(i)], 0L);
            // Last segment uses exact remaining angle to avoid rounding gaps
            double sweep = (i == active.size() - 1)
                ? (startAngle - (90.0 - 360.0))   // = startAngle + 270
                : (360.0 * count / total);
            g.setColor(CHART_COLORS[active.get(i)]);
            g.fillArc(ox, cy - diam / 2, diam, diam,
                (int) Math.round(startAngle), -(int) Math.round(sweep));
            startAngle -= sweep;
        }

        // White center hole — 55% diameter (matches frontend cutout: '55%')
        int holeDiam = (int) (diam * 0.55);
        g.setColor(java.awt.Color.WHITE);
        g.fillOval(centerX - holeDiam / 2, cy - holeDiam / 2, holeDiam, holeDiam);

        // Total count in center
        g.setColor(new java.awt.Color(0x11, 0x18, 0x27));
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 16));
        java.awt.FontMetrics fm = g.getFontMetrics();
        String totalStr = String.valueOf(total);
        g.drawString(totalStr, centerX - fm.stringWidth(totalStr) / 2,
            cy + fm.getAscent() / 2 - 2);

        // Legend — vertically centered next to the chart
        int legendX = ox + diam + 20;
        int legendTopY = cy - active.size() * 11;
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 11));
        for (int i = 0; i < active.size(); i++) {
            int idx = active.get(i);
            long count = bySev.getOrDefault(CHART_SEVERITIES[idx], 0L);
            int iy = legendTopY + i * 22;
            g.setColor(CHART_COLORS[idx]);
            g.fillRoundRect(legendX, iy, 12, 12, 3, 3);
            g.setColor(new java.awt.Color(0x37, 0x41, 0x51));
            String sev = CHART_SEVERITIES[idx];
            g.drawString(
                Character.toUpperCase(sev.charAt(0)) + sev.substring(1) + ": " + count,
                legendX + 17, iy + 11);
        }
    }

    private static void renderBars(java.awt.Graphics2D g, java.util.List<Integer> active,
                                    Map<String, Long> bySev, int W, int H) {
        long maxVal = active.stream()
            .mapToLong(i -> bySev.getOrDefault(CHART_SEVERITIES[i], 0L)).max().orElse(1);

        final int padL = 36, padR = 16, padT = 28, padB = 40;
        int chartW = W - padL - padR;
        int chartH = H - padT - padB;

        // Horizontal grid lines + Y-axis labels
        int gridLines = 4;
        g.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 9));
        for (int i = 0; i <= gridLines; i++) {
            int y = padT + chartH - chartH * i / gridLines;
            g.setColor(new java.awt.Color(0xe5, 0xe7, 0xeb));
            g.drawLine(padL, y, padL + chartW, y);
            long val = maxVal * i / gridLines;
            g.setColor(new java.awt.Color(0x6b, 0x72, 0x80));
            java.awt.FontMetrics fm = g.getFontMetrics();
            String lbl = String.valueOf(val);
            g.drawString(lbl, padL - fm.stringWidth(lbl) - 3, y + 4);
        }

        // Bars
        int n = active.size();
        int slotW = chartW / n;
        int barPad = Math.max(5, slotW / 6);
        int bw = slotW - 2 * barPad;

        for (int i = 0; i < n; i++) {
            int idx = active.get(i);
            long count = bySev.getOrDefault(CHART_SEVERITIES[idx], 0L);
            int bh = (int) (chartH * count / maxVal);
            int bx = padL + i * slotW + barPad;
            int by = padT + chartH - bh;

            // Fill with 80% alpha, border solid (matches frontend 'cc' hex suffix)
            java.awt.Color base = CHART_COLORS[idx];
            g.setColor(new java.awt.Color(base.getRed(), base.getGreen(), base.getBlue(), 200));
            g.fillRoundRect(bx, by, bw, bh, 4, 4);
            g.setColor(base);
            g.drawRoundRect(bx, by, bw, bh, 4, 4);

            // Count above bar
            g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 10));
            g.setColor(new java.awt.Color(0x37, 0x41, 0x51));
            java.awt.FontMetrics fmB = g.getFontMetrics();
            String countStr = String.valueOf(count);
            g.drawString(countStr, bx + bw / 2 - fmB.stringWidth(countStr) / 2, by - 4);

            // X-axis label
            g.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 10));
            g.setColor(new java.awt.Color(0x6b, 0x72, 0x80));
            java.awt.FontMetrics fmL = g.getFontMetrics();
            String sev = CHART_SEVERITIES[idx];
            String sevLbl = Character.toUpperCase(sev.charAt(0)) + sev.substring(1);
            g.drawString(sevLbl, bx + bw / 2 - fmL.stringWidth(sevLbl) / 2,
                padT + chartH + 16);
        }
    }

    private static Long resolveUserId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || "anonymousUser".equals(auth.getName())) return null;
        try { return Long.parseLong(auth.getName()); } catch (NumberFormatException e) { return null; }
    }
}
