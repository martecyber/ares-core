package com.martecyber.ares.imports;

import com.martecyber.ares.assets.AssetLinkType;
import com.martecyber.ares.assets.AssetToolSightingService;
import com.martecyber.ares.assets.AssetType;
import com.martecyber.ares.assets.ServiceVisibilityRepository;
import com.martecyber.ares.assets.ServiceVisibilityState;
import com.martecyber.ares.projects.AssetScopeClassifier;
import com.martecyber.ares.projects.ScopeClassifyScheduler;
import com.martecyber.ares.projects.ProjectAssetAccessRepository;
import com.martecyber.ares.projects.ProjectScopeEntryRepository;
import com.martecyber.ares.detections.Detection;
import com.martecyber.ares.detections.DetectionAffectedAsset;
import com.martecyber.ares.detections.DetectionAffectedAssetRepository;
import com.martecyber.ares.detections.DetectionHttpSampleService;
import com.martecyber.ares.detections.DetectionIterationStatsService;
import com.martecyber.ares.detections.DetectionReferenceExtractor;
import com.martecyber.ares.detections.DetectionUrlReferenceExtractor;
import com.martecyber.ares.detections.DetectionRepository;
import com.martecyber.ares.detections.DetectionStatusHistory;
import com.martecyber.ares.detections.DetectionStatusHistoryRepository;
import com.martecyber.ares.detections.dto.CreateDetectionHttpSampleRequest;
import com.martecyber.ares.projects.Project;
import com.martecyber.ares.projects.ProjectRepository;
import com.martecyber.ares.imports.dto.ScanImportDto;
import com.martecyber.ares.plugins.MissingPluginException;
import com.martecyber.ares.plugins.PluginMessages;
import com.martecyber.ares.plugins.PluginRepository;
import com.martecyber.ares.workflows.WorkflowEventDispatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Service
public class ImportService {

    /** Reserved metadata key carrying port state OPEN/FILTERED/CLOSED for visibility tracking.
     *  Stripped from ParsedAsset.metadata before the asset is persisted. */
    private static final String VISIBILITY_STATE_KEY = AssetMetadataKeys.VISIBILITY_STATE_KEY;

    private final ScanImportRepository importRepo;
    private final ProjectRepository projectRepo;
    private final DetectionRepository detectionRepo;
    private final DetectionAffectedAssetRepository detectionAffectedRepo;
    private final AssetImportHelper assetHelper;
    private final ProjectAssetAccessRepository projectAssetRepo;
    private final ServiceVisibilityRepository visibilityRepo;
    private final AssetToolSightingService sightingService;
    /** Mutable/thread-safe, not the plain injected list — a plugin's own {@code ImportParser}
     *  bean(s) get added/removed at runtime, long after this service's own construction, via
     *  {@link #registerParser}/{@link #unregisterParser} (called from {@code PluginLoader}). Same
     *  pattern as {@code IntegrationService}'s own client list. */
    private final List<ImportParser> parsers;
    private final PluginRepository pluginRepo;
    private final AssetScopeClassifier classifier;
    private final ScopeClassifyScheduler scheduler;
    private final ProjectScopeEntryRepository scopeRepo;
    private final DetectionReferenceExtractor referenceExtractor;
    private final DetectionUrlReferenceExtractor urlReferenceExtractor;
    private final DetectionStatusHistoryRepository historyRepo;
    private final ScanImportChangeRepository changeRepo;
    private final ScanImportRecorder scanImportRecorder;
    private final DetectionHttpSampleService detectionHttpSampleService;
    private final DetectionIterationStatsService iterationStatsService;
    private final com.martecyber.ares.detections.DetectionStatusRepository detectionStatusRepo;
    private final WorkflowEventDispatcher workflowEventDispatcher;
    private final com.martecyber.ares.findings.FindingScoreTypeRepository scoreTypeRepo;
    private final com.martecyber.ares.detections.DetectionScoreRepository detectionScoreRepo;
    private static final Logger log = LoggerFactory.getLogger(ImportService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AsyncImportRunner asyncImportRunner;

    public ImportService(ScanImportRepository importRepo,
                         ProjectRepository projectRepo,
                         DetectionRepository detectionRepo,
                         DetectionAffectedAssetRepository detectionAffectedRepo,
                         AssetImportHelper assetHelper,
                         ProjectAssetAccessRepository projectAssetRepo,
                         ServiceVisibilityRepository visibilityRepo,
                         AssetToolSightingService sightingService,
                         List<ImportParser> parsers,
                         @Lazy AssetScopeClassifier classifier,
                         @Lazy ScopeClassifyScheduler scheduler,
                         ProjectScopeEntryRepository scopeRepo,
                         DetectionReferenceExtractor referenceExtractor,
                         DetectionUrlReferenceExtractor urlReferenceExtractor,
                         DetectionStatusHistoryRepository historyRepo,
                         ScanImportChangeRepository changeRepo,
                         ScanImportRecorder scanImportRecorder,
                         DetectionHttpSampleService detectionHttpSampleService,
                         DetectionIterationStatsService iterationStatsService,
                         com.martecyber.ares.detections.DetectionStatusRepository detectionStatusRepo,
                         WorkflowEventDispatcher workflowEventDispatcher,
                         com.martecyber.ares.findings.FindingScoreTypeRepository scoreTypeRepo,
                         com.martecyber.ares.detections.DetectionScoreRepository detectionScoreRepo,
                         PluginRepository pluginRepo,
                         @Lazy AsyncImportRunner asyncImportRunner) {
        this.asyncImportRunner = asyncImportRunner;
        this.workflowEventDispatcher = workflowEventDispatcher;
        this.scoreTypeRepo = scoreTypeRepo;
        this.detectionScoreRepo = detectionScoreRepo;
        this.importRepo = importRepo;
        this.projectRepo = projectRepo;
        this.detectionRepo = detectionRepo;
        this.detectionAffectedRepo = detectionAffectedRepo;
        this.assetHelper = assetHelper;
        this.projectAssetRepo = projectAssetRepo;
        this.visibilityRepo = visibilityRepo;
        this.sightingService = sightingService;
        this.parsers = new CopyOnWriteArrayList<>(parsers);
        this.pluginRepo = pluginRepo;
        this.classifier = classifier;
        this.scheduler = scheduler;
        this.scopeRepo = scopeRepo;
        this.referenceExtractor = referenceExtractor;
        this.urlReferenceExtractor = urlReferenceExtractor;
        this.historyRepo = historyRepo;
        this.changeRepo = changeRepo;
        this.scanImportRecorder = scanImportRecorder;
        this.detectionHttpSampleService = detectionHttpSampleService;
        this.iterationStatsService = iterationStatsService;
        this.detectionStatusRepo = detectionStatusRepo;
    }

    /** Called by {@code PluginLoader} when a plugin contributing an {@link ImportParser} loads —
     *  see {@link #parsers}' own doc for why the list is mutable. */
    public void registerParser(ImportParser parser) {
        parsers.add(parser);
    }

    public void unregisterParser(ImportParser parser) {
        parsers.remove(parser);
    }

    /** Loaded fresh per import run rather than cached — the table is tiny (AQL implementation
     *  plan, V143) and this matches the rest of the codebase's convention for small lookup
     *  catalogs (e.g. FindingService's status/type name maps). */
    private Map<String, Long> loadDetectionStatusIds() {
        return detectionStatusRepo.findAll().stream()
            .collect(java.util.stream.Collectors.toMap(
                com.martecyber.ares.detections.DetectionStatus::getName,
                com.martecyber.ares.detections.DetectionStatus::getId));
    }

    /** Logs one row/entity this scan import touched, so it can be rolled back later. */
    private void recordDetectionChange(Long scanImportId, Long detectionId, String action, Object prevValuesSnapshot) {
        ScanImportChange c = new ScanImportChange();
        c.setScanImportId(scanImportId);
        c.setEntityType("detection");
        c.setEntityId(detectionId);
        c.setAction(action);
        if (prevValuesSnapshot != null) {
            try { c.setPrevValues(objectMapper.writeValueAsString(prevValuesSnapshot)); }
            catch (Exception e) { log.warn("Failed to snapshot prev values for detection {}: {}", detectionId, e.getMessage()); }
        }
        c.setCreatedAt(OffsetDateTime.now());
        changeRepo.save(c);
    }

    /** Records a detection history event with no actor — every caller here runs in either a
     *  file-upload request thread with no detection-specific auth context, or a scheduled/async
     *  scan-sync job with no propagated SecurityContext, so these are always tool-driven events. */
    private void recordHistory(Long detectionId, String eventType, String fromStatus, String toStatus,
                               OffsetDateTime changedAt) {
        historyRepo.save(new DetectionStatusHistory(
            detectionId, eventType, fromStatus, toStatus, null, null, null, changedAt));
        iterationStatsService.record(detectionId, toStatus, changedAt);
    }

    public com.martecyber.ares.common.PagedResponse<ScanImportDto> listForProject(Long projectId, int page, int size) {
        var pageable = org.springframework.data.domain.PageRequest.of(
            Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        var result = importRepo.findByProjectIdOrderByCreatedAtDesc(projectId, pageable)
            .map(s -> ScanImportDto.from(s, changeRepo.existsByScanImportIdAndRevertedAtIsNull(s.getId())));
        return com.martecyber.ares.common.PagedResponse.of(result);
    }

    /** Single-row counterpart to {@link #listForProject} — lets a client poll one import's
     *  status (e.g. right after {@link #startImport}) without paging through the whole list.
     *  Same not-found-if-wrong-project convention as {@code ScanImportRollbackService#plan}. */
    public com.martecyber.ares.imports.dto.ScanImportDto getForProject(Long projectId, Long scanImportId) {
        ScanImport s = importRepo.findById(scanImportId)
            .orElseThrow(() -> com.martecyber.ares.common.NotFoundException.of("scan_import", scanImportId));
        if (!s.getProjectId().equals(projectId)) {
            throw com.martecyber.ares.common.NotFoundException.of("scan_import", scanImportId);
        }
        return com.martecyber.ares.imports.dto.ScanImportDto.from(s,
            changeRepo.existsByScanImportIdAndRevertedAtIsNull(s.getId()));
    }

    public List<Map<String, Object>> listAvailableTools() {
        return parsers.stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("toolId", p.getToolId());
            m.put("formatId", p.getFormatId());
            m.put("displayName", p.getDisplayName());
            m.put("extensions", p.getSupportedExtensions());
            // Same "toolId doubles as pluginId" join IntegrationController#types uses for its
            // own icon/iconLight — lets a plugin-provided parser's icon show up in the tool
            // picker/history table with no new plugin metadata field needed.
            pluginRepo.findByPluginId(p.getToolId()).ifPresent(plugin -> {
                m.put("icon", plugin.getIcon());
                m.put("iconLight", plugin.getIconLight());
            });
            return m;
        }).toList();
    }

    @Transactional
    public ImportResult runImport(Long projectId, String toolId, String formatId,
                                  String filename, byte[] content) {
        return runImport(projectId, toolId, formatId, filename, content, null, null);
    }

    @Transactional
    public ImportResult runImport(Long projectId, String toolId, String formatId,
                                  String filename, byte[] content, String sourceIp) {
        return runImport(projectId, toolId, formatId, filename, content, sourceIp, null);
    }

    @Transactional
    public ImportResult runImport(Long projectId, String toolId, String formatId,
                                  String filename, byte[] content,
                                  String sourceIp, String nacProfile) {
        return runImport(projectId, toolId, formatId, filename, content, sourceIp, nacProfile, false);
    }

    /**
     * REQUIRES_NEW: callers like AgentTaskService.complete() run this inside their own
     * transaction and catch failures to record a "failed" task status. With the default
     * REQUIRED propagation, a thrown exception here marks the CALLER's transaction
     * rollback-only even after being caught, so the later commit blows up with
     * UnexpectedRollbackException and the "failed" status never persists. Isolating the
     * import into its own transaction lets a failure roll back only its own work.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public ImportResult runImport(Long projectId, String toolId, String formatId,
                                  String filename, byte[] content,
                                  String sourceIp, String nacProfile, boolean scopeFilterDisabled) {
        Prepared p = prepareImport(projectId, toolId, formatId, filename, sourceIp, nacProfile);
        return processImport(p.project(), p.record(), p.parser(), toolId, content,
            p.normalizedSourceIp(), p.normalizedNacProfile(), scopeFilterDisabled);
    }

    /**
     * Non-blocking counterpart to {@link #runImport} — the slow part (parsing + persisting
     * assets/detections, extracting references, dispatching workflow events — everything {@link
     * #processImport} does) can legitimately take minutes for a large result set (a nuclei scan
     * against many hosts routinely embeds a unique, not-yet-cataloged reference URL per finding,
     * each triggering its own synchronous outbound HTTP fetch in {@code
     * DetectionUrlReferenceExtractor} — thousands of those easily exceeds a client's own upload
     * timeout even though the server is still working). Validation and the {@code ScanImport}
     * row creation stay synchronous — a bad tool/plugin id still fails the request immediately,
     * exactly like {@link #runImport} — only the processing itself is handed over to {@link
     * AsyncImportRunner}. Callers read progress the same way they already do for any other
     * import: the returned row's own {@code status} ({@code "running"} until the background work
     * flips it to {@code "completed"}/{@code "failed"}), polled via the existing imports list —
     * no new notification mechanism needed.
     *
     * @param onComplete optional — invoked with the final {@link ImportResult} once processing
     *     finishes, on the background thread (never the caller's). {@code null} for callers (the
     *     plain file-upload endpoint) that have nothing further to do once the row itself
     *     reflects the outcome; {@code AgentTaskService} passes one to update its own task row.
     */
    public ScanImport startImport(Long projectId, String toolId, String formatId,
                                  String filename, byte[] content,
                                  String sourceIp, String nacProfile, boolean scopeFilterDisabled,
                                  java.util.function.Consumer<ImportResult> onComplete) {
        Prepared p = prepareImport(projectId, toolId, formatId, filename, sourceIp, nacProfile);
        asyncImportRunner.processAsync(p.project(), p.record(), p.parser(), toolId, content,
            p.normalizedSourceIp(), p.normalizedNacProfile(), scopeFilterDisabled, onComplete);
        return p.record();
    }

    /**
     * Same validation + "running" {@code ScanImport} row creation {@link #runImport} always did
     * first — split out so {@link #startImport} can do the exact same fast, synchronous prefix
     * (so a bad tool/plugin id still fails immediately, before any background work is queued)
     * and then hand the slow part to {@link AsyncImportRunner} instead of running it inline.
     */
    private Prepared prepareImport(Long projectId, String toolId, String formatId,
                                    String filename, String sourceIp, String nacProfile) {
        Project project = projectRepo.findById(projectId)
            .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        ImportParser parser = findParser(toolId, formatId);
        if (parser == null) {
            // A Plugin row for this toolId means the tool genuinely exists but its parser isn't
            // currently registered (not installed/enabled, or that specific format isn't
            // implemented) — a structured, actionable error, same as everywhere else a plugin
            // might be missing. No matching Plugin row at all means toolId itself is bogus.
            if (pluginRepo.findByPluginId(toolId).isPresent()) {
                throw new MissingPluginException(toolId,
                    PluginMessages.missingPluginMessage(pluginRepo, "Import tool", toolId));
            }
            throw new IllegalArgumentException("No parser found for tool=" + toolId + " format=" + formatId);
        }

        String normalizedSourceIp   = (sourceIp   == null || sourceIp.isBlank())   ? null : sourceIp.trim();
        // NAC profile is a free-text tag. Stored as "" in service_visibility (NOT NULL key),
        // but kept as nullable on scan_import for clearer audit display ("no profile declared").
        String normalizedNacProfile = (nacProfile == null || nacProfile.isBlank()) ? null : nacProfile.trim();

        ScanImport draft = new ScanImport();
        draft.setProjectId(projectId);
        draft.setOrganizationId(project.getOrganizationId());
        draft.setTool(toolId);
        draft.setFormat(formatId != null ? formatId : "default");
        draft.setStatus("running");
        draft.setFilename(filename);
        draft.setSourceIp(normalizedSourceIp);
        draft.setNacProfile(normalizedNacProfile);
        draft.setCreatedAt(OffsetDateTime.now());
        // Committed immediately (its own transaction) — asset resolution below runs in
        // REQUIRES_NEW sub-transactions that need this row to already be durably visible
        // before they can insert a scan_import_change FK-referencing it.
        ScanImport record = scanImportRecorder.commit(draft);
        return new Prepared(project, record, parser, normalizedSourceIp, normalizedNacProfile);
    }

    private record Prepared(Project project, ScanImport record, ImportParser parser,
                             String normalizedSourceIp, String normalizedNacProfile) {}

    /**
     * Synchronous entry point used by API/CLI uploads and agent task completion alike until
     * this method — kept for the 39-odd existing tests and any other direct caller that wants
     * to block until the import is fully processed. {@link #startImport} is the same validation
     * + record-creation prefix, but hands the actual parsing/persistence to a background thread
     * instead of blocking the caller — see that method's own doc for why.
     */
    @Transactional
    ImportResult processImport(Project project, ScanImport record, ImportParser parser, String toolId,
                                byte[] content, String normalizedSourceIp, String normalizedNacProfile,
                                boolean scopeFilterDisabled) {
        Long projectId = project.getId();
        AssetImportHelper.setCurrentScanImportId(record.getId());

        ImportResult result = new ImportResult();
        try {
            // Empty output (e.g. nuclei found no results) is a valid zero-result run — not an error.
            // Skip parsing entirely and return success with zero counts.
            if (content == null || content.length == 0) {
                result.setSuccess(true);
                result.setMessage("Import completed: 0 assets, 0 new detections, 0 updated");
                record.setStatus("completed");
                record.setAssetsCreated(0);
                record.setDetectionsCreated(0);
                record.setDetectionsUpdated(0);
                record.setCompletedAt(OffsetDateTime.now());
                importRepo.save(record);
                return result;
            }

            if (!parser.validate(content)) {
                throw new IllegalArgumentException("File content does not match expected format for " + parser.getDisplayName());
            }

            // Mapped to ParseContext's own lightweight view record — ParseContext lives in
            // ares-sdk now, which has no dependency on this entity (or ares-core at all).
            var scopeEntryViews = scopeRepo.findByProjectIdOrderByCreatedAtAsc(projectId).stream()
                .map(e -> new ParseContext.ScopeEntryView(e.getKind(), e.getValue(), e.isInScope()))
                .toList();
            ParseContext parseCtx = ParseContext.forScope(scopeEntryViews, scopeFilterDisabled);
            ParseResult parsed = parser.parse(content, parseCtx);
            result.getWarnings().addAll(parsed.getWarnings());
            result.getErrors().addAll(parsed.getErrors());

            // Deduplicate assets by (identifier, type) before persisting — prevents duplicate
            // inserts from parsers that emit the same asset multiple times (e.g. Nmap re-emits
            // an IP asset once per host block when a host has multiple scan entries), while
            // still keeping two DIFFERENT-typed assets that legitimately share an identifier
            // string (e.g. a HOST and a DOMAIN both named after the same resolved hostname)
            // as two distinct rows instead of silently collapsing one into the other.
            List<ParsedAsset> uniqueAssets = parsed.getAssets().stream()
                .collect(Collectors.toMap(
                    pa -> assetKey(pa.getIdentifier(), pa.getType()),
                    a -> a,
                    (a, b) -> a,          // keep first occurrence
                    LinkedHashMap::new
                ))
                .values().stream().toList();

            // Capture reserved-key values (visibility state) keyed by identifier, then strip
            // them from SERVICE metadata so they don't leak into Asset JSONB. Only SERVICE
            // parsers put this key in, and their metadata maps are mutable (LinkedHashMap) —
            // other asset types may use immutable Map.of() so we don't touch them.
            Map<String, String> visibilityStateByIdentifier = new HashMap<>();
            for (ParsedAsset pa : uniqueAssets) {
                if (!AssetType.SERVICE.equals(pa.getType()) || pa.getMetadata() == null) continue;
                Object state = pa.getMetadata().remove(VISIBILITY_STATE_KEY);
                if (state != null) visibilityStateByIdentifier.put(pa.getIdentifier(), state.toString());
            }

            // Resolve/create assets via AssetImportHelper (REQUIRES_NEW per asset so a
            // constraint violation on one asset doesn't poison the main transaction).
            // assetIdByIdentifier stays identifier-only (last write wins on a type collision)
            // for callers below that filter by type before consulting it — SERVICE/WEB_APPLICATION
            // identifiers never collide with anything else in practice. assetIdByIdentifierAndType
            // and allResolvedAssetIds exist specifically so a HOST/DOMAIN (or similar) identifier
            // collision doesn't lose one of the two assets from link-resolution or project-linking.
            Map<String, Long> assetIdByIdentifier = new HashMap<>();
            Map<String, Long> assetIdByIdentifierAndType = new HashMap<>();
            List<Long> allResolvedAssetIds = new ArrayList<>();
            int assetsCreated = 0;
            for (ParsedAsset pa : uniqueAssets) {
                try {
                    Long assetId = assetHelper.resolveOrCreate(project.getOrganizationId(), pa);
                    if (assetId != null) {
                        assetIdByIdentifier.put(pa.getIdentifier(), assetId);
                        assetIdByIdentifierAndType.put(assetKey(pa.getIdentifier(), pa.getType()), assetId);
                        allResolvedAssetIds.add(assetId);
                        assetsCreated++;
                        sightingService.upsert(assetId, project.getId(), toolId);
                    }
                } catch (Exception e) {
                    result.addWarning("Skipped asset '" + pa.getIdentifier() + "': " + e.getMessage());
                }
            }
            result.setAssetsCreated(assetsCreated);

            // Service visibility: for each SERVICE asset emitted in this import, upsert a row
            // (service_asset_id, source_ip, nac_profile) → state. Skipped if the operator
            // didn't declare a sourceIp, or if no SERVICE assets were emitted (non-port-scan tools).
            if (normalizedSourceIp != null) {
                // Empty string is the canonical "no NAC profile" — NOT NULL keeps the unique
                // constraint working (PostgreSQL treats NULLs as distinct).
                String nacKey = normalizedNacProfile != null ? normalizedNacProfile : "";
                int visibilityRecorded = 0;
                for (ParsedAsset pa : uniqueAssets) {
                    if (!AssetType.SERVICE.equals(pa.getType())) continue;
                    Long assetId = assetIdByIdentifier.get(pa.getIdentifier());
                    if (assetId == null) continue;
                    ServiceVisibilityState state = ServiceVisibilityState.parse(
                        visibilityStateByIdentifier.get(pa.getIdentifier()));
                    try {
                        visibilityRepo.upsert(assetId, normalizedSourceIp, nacKey, state.name(), record.getId());
                        visibilityRecorded++;
                    } catch (Exception e) {
                        result.addWarning("Skipped visibility for '" + pa.getIdentifier() + "': " + e.getMessage());
                    }
                }
                result.setVisibilityRecorded(visibilityRecorded);
                record.setVisibilityRecorded(visibilityRecorded);
            }

            // Link every resolved asset to this project (idempotent — ON CONFLICT DO NOTHING).
            // If the asset already existed in the org inventory, this makes it visible in the
            // project. If it was just created, it gets added to inventory AND made visible.
            // Iterates allResolvedAssetIds (not assetIdByIdentifier.values()) so a HOST/DOMAIN
            // identifier collision can't cause one of the two to be silently skipped here too.
            for (Long assetId : allResolvedAssetIds) {
                try {
                    projectAssetRepo.linkIfAbsent(projectId, assetId);
                } catch (Exception e) {
                    result.addWarning("Could not link asset " + assetId + " to project: " + e.getMessage());
                }
            }

            // Process asset links declared by parsers (host→interface→ip→service chains, etc.)
            for (ParsedAssetLink link : parsed.getLinks()) {
                try {
                    String[] expected = AssetLinkType.expectedTypes(link.getLinkType());
                    Long fromId = resolveLinkEndpoint(link.getFromIdentifier(),
                        expected != null ? expected[0] : null, assetIdByIdentifierAndType, assetIdByIdentifier);
                    Long toId   = resolveLinkEndpoint(link.getToIdentifier(),
                        expected != null ? expected[1] : null, assetIdByIdentifierAndType, assetIdByIdentifier);
                    if (fromId != null && toId != null) {
                        assetHelper.linkIfAbsent(fromId, toId, link.getLinkType());
                    }
                } catch (Exception e) {
                    // result.addWarning alone never reaches the container logs (only the HTTP
                    // response body) — log it too, so a link failure that poisons the surrounding
                    // @Transactional import (any REQUIRED-propagation call that throws marks the
                    // whole transaction rollback-only, even once caught here) is diagnosable from
                    // `docker logs` alone instead of silently surfacing as UnexpectedRollbackException.
                    log.warn("Skipped link {} -> {} ({}): {}", link.getFromIdentifier(), link.getToIdentifier(),
                        link.getLinkType(), e.getMessage(), e);
                    result.addWarning("Skipped link " + link.getFromIdentifier() + " → " +
                        link.getToIdentifier() + ": " + e.getMessage());
                }
            }

            // Ensure every WEB_APPLICATION has a root WEB_ENDPOINT linked via WEBAPP_ENDPOINT
            ensureWebEndpoints(parsed, assetIdByIdentifier, assetIdByIdentifierAndType, project.getOrganizationId(), projectId);

            // Final pass: ensure every service asset in this import is linked to its interface.
            // Uses assetIdByIdentifier (populated for both new and pre-existing assets) so it
            // also repairs services that were imported before the link logic existed.
            // Pattern: service identifier = "{ip}:{port}/{proto}"
            for (Map.Entry<String, Long> e : assetIdByIdentifier.entrySet()) {
                String identifier = e.getKey();
                int lastColon = identifier.lastIndexOf(':');
                if (lastColon <= 0) continue;
                String ipPart = identifier.substring(0, lastColon);
                // Quick check: only bare IPv4 → this is a service asset
                if (!ipPart.matches("\\d{1,3}(\\.\\d{1,3}){3}")) continue;
                Long svcId   = e.getValue();
                Long ifaceId = assetIdByIdentifier.get("iface-" + ipPart);
                if (ifaceId == null) continue; // interface not in this import — skip
                try {
                    assetHelper.linkIfAbsent(ifaceId, svcId, "interface_service");
                } catch (Exception ex) {
                    result.addWarning("Skipped service link for " + identifier + ": " + ex.getMessage());
                }
            }

            // Upsert detections by dedup hash
            OffsetDateTime now = OffsetDateTime.now();
            Map<String, Long> statusIds = loadDetectionStatusIds();
            for (ParsedDetection pd : parsed.getDetections()) {
                Long assetId = pd.getAssetIdentifier() != null
                    ? assetIdByIdentifier.get(pd.getAssetIdentifier()) : null;

                String hash = computeDedupHash(projectId, assetId, toolId, pd.getSourceTemplateId(), pd.getTitle());

                Optional<Detection> existing = detectionRepo.findByProjectIdAndDedupHash(projectId, hash);
                if (existing.isPresent()) {
                    Detection d = existing.get();
                    String fromStatus = d.getStatus();
                    Map<String, Object> before = new LinkedHashMap<>();
                    before.put("occurrenceCount", d.getOccurrenceCount());
                    before.put("lastSeen", d.getLastSeen() != null ? d.getLastSeen().toString() : null);
                    before.put("status", d.getStatus());
                    d.setOccurrenceCount(d.getOccurrenceCount() + 1);
                    d.setLastSeen(now);
                    // Refresh every tool-reported field from the latest scan/sync data on each
                    // re-detection — these aren't analyst-owned (unlike status, which
                    // applyStateTransition below governs on its own terms), so a detection's
                    // severity/priority/title/description/sourceTemplateId/rawData/CVSS score
                    // should track what the source currently reports on every re-sync, the same
                    // way regardless of which integration produced it — not stay frozen at
                    // whatever the very first import happened to see.
                    d.setSourceTemplateId(pd.getSourceTemplateId());
                    d.setTitle(truncate(stripNulBytes(pd.getTitle()), 300));
                    d.setSeverity(pd.getSeverity());
                    d.setPriority(com.martecyber.ares.common.PriorityThresholds.fromSeverityName(pd.getSeverity()));
                    d.setDescription(stripNulBytes(pd.getDescription()));
                    d.setRawData(stripNulBytes(pd.getRawData()));
                    d.setUpdatedAt(now);
                    boolean statusChanged = applyStateTransition(d, pd, now, statusIds);
                    Detection saved = detectionRepo.save(d);
                    recordDetectionChange(record.getId(), saved.getId(), "updated", before);
                    if (statusChanged) {
                        recordHistory(saved.getId(), "status_changed", fromStatus, saved.getStatus(), now);
                    } else {
                        recordHistory(saved.getId(), "reseen", null, null, now);
                    }
                    createHttpSampleIfPresent(pd, saved.getId(), toolId);
                    refreshDefaultScore(pd, saved.getId());
                    result.setDetectionsUpdated(result.getDetectionsUpdated() + 1);
                } else {
                    Detection d = new Detection();
                    d.setProjectId(projectId);
                    d.setAssetId(assetId);
                    d.setTitle(truncate(stripNulBytes(pd.getTitle()), 300));
                    d.setSeverity(pd.getSeverity());
                    d.setPriority(com.martecyber.ares.common.PriorityThresholds.fromSeverityName(pd.getSeverity()));
                    d.setStatus(initialStatus(pd));
                    d.setStatusId(statusIds.get(d.getStatus()));
                    d.setDescription(stripNulBytes(pd.getDescription()));
                    d.setRawData(stripNulBytes(pd.getRawData()));
                    d.setSourceType(toolId);
                    d.setSourceTemplateId(pd.getSourceTemplateId());
                    d.setDedupHash(hash);
                    d.setCreatedAt(now);
                    d.setUpdatedAt(now);
                    d.setLastSeen(now);
                    Detection saved = detectionRepo.save(d);
                    recordDetectionChange(record.getId(), saved.getId(), "created", null);
                    // Default: detected_at asset is the initial "affected" asset, unless the
                    // parser declared a different one (e.g. Burp: detected_at the endpoint,
                    // affects the parent web application). Operators can widen later via
                    // PUT /affected-assets.
                    Long affectsAssetId = resolveAffectsAssetId(pd, saved.getAssetId(), assetIdByIdentifier);
                    if (affectsAssetId != null) {
                        detectionAffectedRepo.save(
                            new DetectionAffectedAsset(saved.getId(), affectsAssetId));
                    }
                    createHttpSampleIfPresent(pd, saved.getId(), toolId);
                    createScoreIfPresent(pd, saved.getId());
                    referenceExtractor.extractAndLink(saved);
                    urlReferenceExtractor.extractAndLink(saved);
                    recordHistory(saved.getId(), "created", null, saved.getStatus(), now);
                    workflowEventDispatcher.onDetectionCreated(saved);
                    result.setDetectionsCreated(result.getDetectionsCreated() + 1);
                }
            }

            result.setSuccess(true);
            StringBuilder msg = new StringBuilder(String.format(
                "Import completed: %d assets, %d new detections, %d updated",
                result.getAssetsCreated(), result.getDetectionsCreated(), result.getDetectionsUpdated()));
            if (result.getVisibilityRecorded() > 0) {
                msg.append(String.format(", %d visibility records", result.getVisibilityRecorded()));
            }
            result.setMessage(msg.toString());
            record.setStatus("completed");
            record.setAssetsCreated(result.getAssetsCreated());
            record.setDetectionsCreated(result.getDetectionsCreated());
            record.setDetectionsUpdated(result.getDetectionsUpdated());

        } catch (Exception ex) {
            result.setSuccess(false);
            result.setMessage(ex.getMessage());
            result.addError(ex.getMessage());
            record.setStatus("failed");
            record.setErrorMessage(ex.getMessage());
        } finally {
            AssetImportHelper.clearCurrentScanImportId();
        }

        // Re-classify scope after every import: new assets linked by linkIfAbsent above
        // start with the default 'indeterminate' status and need the classifier to evaluate
        // them against the project's existing scope entries.
        if (result.isSuccess()) {
            try { scheduler.schedule(projectId); } catch (Exception ignored) {}
        }

        record.setCompletedAt(OffsetDateTime.now());
        importRepo.save(record);
        return result;
    }

    /**
     * Processes a ParseResult (already produced) for a given project.
     * Can be called by API integrations (Tenable, Qualys…) that produce ParseResults
     * without going through the file-upload path.
     *
     * Note: this path does NOT populate service_visibility — API-driven integrations
     * don't carry a meaningful "source IP" / vantage point. Visibility tracking is
     * specific to file imports from port scanners (nmap/masscan/naabu).
     */
    @Transactional
    public ImportResult processParseResult(Long projectId, Long orgId, String sourceType, ParseResult parsed) {
        Project project = projectRepo.findById(projectId)
            .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));

        // Record the API-driven import for audit
        ScanImport draft = new ScanImport();
        draft.setProjectId(projectId);
        draft.setOrganizationId(orgId != null ? orgId : project.getOrganizationId());
        draft.setTool(sourceType);
        draft.setFormat("api");
        draft.setStatus("running");
        draft.setCreatedAt(OffsetDateTime.now());
        ScanImport record = scanImportRecorder.commit(draft);
        AssetImportHelper.setCurrentScanImportId(record.getId());

        ImportResult result = new ImportResult();
        try {
            result.getWarnings().addAll(parsed.getWarnings());
            result.getErrors().addAll(parsed.getErrors());

            List<ParsedAsset> uniqueAssets = parsed.getAssets().stream()
                .collect(java.util.stream.Collectors.toMap(
                    pa -> assetKey(pa.getIdentifier(), pa.getType()), a -> a, (a, b) -> a,
                    java.util.LinkedHashMap::new))
                .values().stream().toList();

            // Strip the reserved visibility-state key from any SERVICE metadata so it
            // doesn't leak into Asset JSONB. API integrations don't use the new emit
            // overload today, but this keeps the invariant in one place.
            for (ParsedAsset pa : uniqueAssets) {
                if (AssetType.SERVICE.equals(pa.getType()) && pa.getMetadata() != null) {
                    pa.getMetadata().remove(VISIBILITY_STATE_KEY);
                }
            }

            // See runImport() for why both an identifier-only map and a (identifier,type)-aware
            // map + resolved-id list are kept — prevents a HOST/DOMAIN (or similar) identifier
            // collision from silently losing one of the two assets.
            Map<String, Long> assetIdByIdentifier = new HashMap<>();
            Map<String, Long> assetIdByIdentifierAndType = new HashMap<>();
            List<Long> allResolvedAssetIds = new ArrayList<>();
            int assetsCreated = 0;
            for (ParsedAsset pa : uniqueAssets) {
                try {
                    Long assetId = assetHelper.resolveOrCreate(project.getOrganizationId(), pa);
                    if (assetId != null) {
                        assetIdByIdentifier.put(pa.getIdentifier(), assetId);
                        assetIdByIdentifierAndType.put(assetKey(pa.getIdentifier(), pa.getType()), assetId);
                        allResolvedAssetIds.add(assetId);
                        assetsCreated++;
                        sightingService.upsert(assetId, project.getId(), sourceType);
                    }
                } catch (Exception e) {
                    result.addWarning("Skipped asset '" + pa.getIdentifier() + "': " + e.getMessage());
                }
            }
            result.setAssetsCreated(assetsCreated);

            for (Long assetId : allResolvedAssetIds) {
                try { projectAssetRepo.linkIfAbsent(projectId, assetId); }
                catch (Exception e) { result.addWarning("Could not link asset " + assetId + ": " + e.getMessage()); }
            }

            for (ParsedAssetLink link : parsed.getLinks()) {
                try {
                    String[] expected = AssetLinkType.expectedTypes(link.getLinkType());
                    Long fromId = resolveLinkEndpoint(link.getFromIdentifier(),
                        expected != null ? expected[0] : null, assetIdByIdentifierAndType, assetIdByIdentifier);
                    Long toId   = resolveLinkEndpoint(link.getToIdentifier(),
                        expected != null ? expected[1] : null, assetIdByIdentifierAndType, assetIdByIdentifier);
                    if (fromId == null || toId == null) continue;
                    // Exclusive links: one owner per target (interface→host, ip→interface)
                    if (com.martecyber.ares.assets.AssetLinkType.HOST_INTERFACE.equals(link.getLinkType())
                            || com.martecyber.ares.assets.AssetLinkType.INTERFACE_IP.equals(link.getLinkType())) {
                        assetHelper.linkExclusive(fromId, toId, link.getLinkType());
                    } else {
                        assetHelper.linkIfAbsent(fromId, toId, link.getLinkType());
                    }
                } catch (Exception e) {
                    // See the identical comment in runImport()'s link-processing loop: without
                    // this, a poisoned transaction from here surfaces only as an unexplained
                    // UnexpectedRollbackException, since result.addWarning never reaches the logs.
                    log.warn("Skipped link {} -> {} ({}): {}", link.getFromIdentifier(), link.getToIdentifier(),
                        link.getLinkType(), e.getMessage(), e);
                    result.addWarning("Skipped link: " + e.getMessage());
                }
            }

            // Ensure every WEB_APPLICATION has a root WEB_ENDPOINT linked via WEBAPP_ENDPOINT
            ensureWebEndpoints(parsed, assetIdByIdentifier, assetIdByIdentifierAndType, record.getOrganizationId(), projectId);

            OffsetDateTime now = OffsetDateTime.now();
            Map<String, Long> statusIds = loadDetectionStatusIds();
            for (ParsedDetection pd : parsed.getDetections()) {
                Long assetId = pd.getAssetIdentifier() != null ? assetIdByIdentifier.get(pd.getAssetIdentifier()) : null;
                String hash = computeDedupHash(projectId, assetId, sourceType, pd.getSourceTemplateId(), pd.getTitle());
                Optional<Detection> existing = detectionRepo.findByProjectIdAndDedupHash(projectId, hash);
                if (existing.isPresent()) {
                    Detection d = existing.get();
                    String fromStatus = d.getStatus();
                    Map<String, Object> before = new LinkedHashMap<>();
                    before.put("occurrenceCount", d.getOccurrenceCount());
                    before.put("lastSeen", d.getLastSeen() != null ? d.getLastSeen().toString() : null);
                    before.put("status", d.getStatus());
                    d.setOccurrenceCount(d.getOccurrenceCount() + 1);
                    d.setLastSeen(now);
                    // Refresh every tool-reported field from the latest scan/sync data on each
                    // re-detection — these aren't analyst-owned (unlike status, which
                    // applyStateTransition below governs on its own terms), so a detection's
                    // severity/priority/title/description/sourceTemplateId/rawData/CVSS score
                    // should track what the source currently reports on every re-sync, the same
                    // way regardless of which integration produced it — not stay frozen at
                    // whatever the very first import happened to see.
                    d.setSourceTemplateId(pd.getSourceTemplateId());
                    d.setTitle(truncate(stripNulBytes(pd.getTitle()), 300));
                    d.setSeverity(pd.getSeverity());
                    d.setPriority(com.martecyber.ares.common.PriorityThresholds.fromSeverityName(pd.getSeverity()));
                    d.setDescription(stripNulBytes(pd.getDescription()));
                    d.setRawData(stripNulBytes(pd.getRawData()));
                    d.setUpdatedAt(now);
                    boolean statusChanged = applyStateTransition(d, pd, now, statusIds);
                    Detection saved = detectionRepo.save(d);
                    recordDetectionChange(record.getId(), saved.getId(), "updated", before);
                    if (statusChanged) {
                        recordHistory(saved.getId(), "status_changed", fromStatus, saved.getStatus(), now);
                    } else {
                        recordHistory(saved.getId(), "reseen", null, null, now);
                    }
                    createHttpSampleIfPresent(pd, saved.getId(), sourceType);
                    refreshDefaultScore(pd, saved.getId());
                    result.setDetectionsUpdated(result.getDetectionsUpdated() + 1);
                } else {
                    Detection d = new Detection();
                    d.setProjectId(projectId);
                    d.setAssetId(assetId);
                    d.setTitle(truncate(stripNulBytes(pd.getTitle()), 300));
                    d.setSeverity(pd.getSeverity());
                    d.setPriority(com.martecyber.ares.common.PriorityThresholds.fromSeverityName(pd.getSeverity()));
                    d.setStatus(initialStatus(pd));
                    d.setStatusId(statusIds.get(d.getStatus()));
                    d.setDescription(stripNulBytes(pd.getDescription()));
                    d.setRawData(stripNulBytes(pd.getRawData()));
                    d.setSourceType(sourceType);
                    d.setSourceTemplateId(pd.getSourceTemplateId());
                    d.setDedupHash(hash);
                    d.setCreatedAt(now);
                    d.setUpdatedAt(now);
                    d.setLastSeen(now);
                    Detection saved = detectionRepo.save(d);
                    recordDetectionChange(record.getId(), saved.getId(), "created", null);
                    // Default: detected_at asset is the initial "affected" asset, unless the
                    // parser declared a different one (e.g. Burp: detected_at the endpoint,
                    // affects the parent web application). Operators can widen later via
                    // PUT /affected-assets.
                    Long affectsAssetId = resolveAffectsAssetId(pd, saved.getAssetId(), assetIdByIdentifier);
                    if (affectsAssetId != null) {
                        detectionAffectedRepo.save(
                            new DetectionAffectedAsset(saved.getId(), affectsAssetId));
                    }
                    createHttpSampleIfPresent(pd, saved.getId(), sourceType);
                    createScoreIfPresent(pd, saved.getId());
                    referenceExtractor.extractAndLink(saved);
                    urlReferenceExtractor.extractAndLink(saved);
                    recordHistory(saved.getId(), "created", null, saved.getStatus(), now);
                    workflowEventDispatcher.onDetectionCreated(saved);
                    result.setDetectionsCreated(result.getDetectionsCreated() + 1);
                }
            }

            result.setSuccess(true);
            result.setMessage(String.format("API sync completed: %d assets, %d new detections, %d updated",
                result.getAssetsCreated(), result.getDetectionsCreated(), result.getDetectionsUpdated()));
            record.setStatus("completed");
            record.setAssetsCreated(result.getAssetsCreated());
            record.setDetectionsCreated(result.getDetectionsCreated());
            record.setDetectionsUpdated(result.getDetectionsUpdated());

        } catch (Exception ex) {
            result.setSuccess(false);
            result.setMessage(ex.getMessage());
            result.addError(ex.getMessage());
            record.setStatus("failed");
            record.setErrorMessage(ex.getMessage());
        } finally {
            AssetImportHelper.clearCurrentScanImportId();
        }

        if (result.isSuccess()) {
            try { scheduler.schedule(projectId); } catch (Exception ignored) {}
        }

        record.setCompletedAt(OffsetDateTime.now());
        importRepo.save(record);
        return result;
    }

    // Statuses explicitly set by a user analyst — never overridden by automated sync
    private static final Set<String> USER_CLOSED = Set.of(
        "not_affected", "ignored"
    );

    /**
     * Returns the initial status for a newly created detection based on the tool state.
     * fixed → "fixed"  |  open/reopened/unknown → "new"
     */
    private static String initialStatus(ParsedDetection pd) {
        return pd.isFixed() ? "fixed" : "new";
    }

    /**
     * Applies tool-driven status transitions to an existing detection.
     *
     * Rules:
     *   fixed   + current ∈ {new, reopened, affected}    → fixed
     *   active  + current = "fixed"                      → reopened
     *   All user-set statuses (not_affected, ignored) and research states (under_investigation)
     *   are untouched — under_investigation isn't in USER_CLOSED but is already safe here since
     *   neither branch above lists it as a recognized "current" value.
     */
    private static boolean applyStateTransition(Detection d, ParsedDetection pd, OffsetDateTime now, Map<String, Long> statusIds) {
        String cur = d.getStatus();
        if (USER_CLOSED.contains(cur)) return false;

        if (pd.isFixed()) {
            if ("new".equals(cur) || "reopened".equals(cur) || "affected".equals(cur)) {
                d.setStatus("fixed");
                d.setStatusId(statusIds.get("fixed"));
                d.setUpdatedAt(now);
                return true;
            }
        } else if (pd.isActive() && "fixed".equals(cur)) {
            d.setStatus("reopened");
            d.setStatusId(statusIds.get("reopened"));
            d.setUpdatedAt(now);
            return true;
        }
        return false;
    }

    /** Composite key so two ParsedAssets that legitimately share an identifier string but
     *  differ in type (e.g. a HOST and a DOMAIN both named after the same resolved hostname)
     *  are never conflated during dedup/lookup. */
    private static String assetKey(String identifier, String type) {
        return identifier + '\u0001' + type;
    }

    /**
     * Resolves a ParsedAssetLink endpoint identifier to an asset ID, preferring the type
     * implied by the link's own linkType (via {@link AssetLinkType#expectedTypes}) so an
     * identifier collision across asset types resolves to the correct one instead of
     * whichever type happened to be created/kept first. Falls back to the plain
     * identifier-only map for link types the matrix doesn't recognize.
     */
    private static Long resolveLinkEndpoint(String identifier, String expectedType,
                                            Map<String, Long> byIdentifierAndType,
                                            Map<String, Long> byIdentifier) {
        if (expectedType != null) {
            Long id = byIdentifierAndType.get(assetKey(identifier, expectedType));
            if (id != null) return id;
        }
        return byIdentifier.get(identifier);
    }

    private String computeDedupHash(Long projectId, Long assetId, String sourceType,
                                     String templateId, String title) {
        String key = projectId + "|" + sourceType + "|" + (assetId != null ? assetId : "null") + "|";
        key += (templateId != null && !templateId.isBlank())
            ? templateId
            : (title != null ? title.trim().toLowerCase() : "");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString().replace("-", "");
        }
    }

    private ImportParser findParser(String toolId, String formatId) {
        String fmt = (formatId == null || formatId.isBlank()) ? "default" : formatId;
        return parsers.stream()
            .filter(p -> p.getToolId().equals(toolId) && p.getFormatId().equals(fmt))
            .findFirst().orElse(null);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Resolves the DetectionAffectedAsset target: the parser-declared "affects" identifier
     *  (e.g. Burp's parent web application) when it resolved to a real asset in this import,
     *  otherwise the detection's own detected_at asset — matching every importer's original
     *  default behavior before affectsIdentifier existed. */
    private Long resolveAffectsAssetId(ParsedDetection pd, Long detectedAtAssetId, Map<String, Long> assetIdByIdentifier) {
        if (pd.getAffectsIdentifier() != null) {
            Long affectsAssetId = assetIdByIdentifier.get(pd.getAffectsIdentifier());
            if (affectsAssetId != null) return affectsAssetId;
        }
        return detectedAtAssetId;
    }

    /** Stores the parser-captured raw request/response (e.g. Burp's per-issue proof-of-concept
     *  capture) as a DetectionHttpSample, so it's associated with the specific detection it
     *  belongs to. No-op when the parser didn't capture anything for this finding. */
    private void createHttpSampleIfPresent(ParsedDetection pd, Long detectionId, String label) {
        String request = pd.getRequestContent();
        String response = pd.getResponseContent();
        if ((request == null || request.isBlank()) && (response == null || response.isBlank())) return;
        try {
            detectionHttpSampleService.create(detectionId, new CreateDetectionHttpSampleRequest(
                label, stripNulBytes(request), stripNulBytes(response), null));
        } catch (Exception e) {
            log.warn("Failed to store HTTP sample for detection {}: {}", detectionId, e.getMessage());
        }
    }

    /** Strip \u0000 (actual null byte) and its JSON-escaped text form that PG rejects in text/jsonb. */
    /** Persists a DetectionScore (e.g. Tenable's per-plugin CVSS base score) when the parser
     *  captured one on the ParsedDetection. No-op when the parser didn't set a score, or when
     *  its version string doesn't match a seeded finding_score_type.title (defensive — should
     *  never happen for the versions parsers are expected to use: "CVSS 3.1"/"CVSS 2.0"). */
    /** Update-path counterpart to {@link #createScoreIfPresent} — re-syncing a detection should
     *  refresh its default CVSS score the same way it now refreshes severity/title/description
     *  (see the update branches in runImport/processParseResult), instead of leaving whatever the
     *  detection's very first import reported permanently stuck. Upserts by (detectionId,
     *  isDefault=true) rather than always inserting, so a re-sync updates the one existing
     *  default row (score type included, in case the source starts reporting a different CVSS
     *  version) instead of accumulating a duplicate every time. A source that no longer reports a
     *  score at all (pd.getCvssScore() null) leaves any existing row alone rather than deleting
     *  it — a transient parse gap shouldn't destroy previously-known data. */
    private void refreshDefaultScore(ParsedDetection pd, Long detectionId) {
        if (pd.getCvssScore() == null || pd.getCvssVersion() == null) return;
        scoreTypeRepo.findByTitle(pd.getCvssVersion()).ifPresentOrElse(type -> {
            String metadata = null;
            if (pd.getCvssVector() != null && !pd.getCvssVector().isBlank()) {
                try {
                    metadata = objectMapper.writeValueAsString(Map.of("vector", pd.getCvssVector()));
                } catch (Exception ignored) { /* best-effort, score itself still persists */ }
            }
            com.martecyber.ares.detections.DetectionScore score = detectionScoreRepo
                .findByDetectionIdAndIsDefaultTrue(detectionId)
                .orElseGet(() -> {
                    com.martecyber.ares.detections.DetectionScore s = new com.martecyber.ares.detections.DetectionScore();
                    s.setDetectionId(detectionId);
                    s.setDefault(true);
                    s.setCreatedAt(OffsetDateTime.now());
                    return s;
                });
            score.setTypeId(type.getId());
            score.setScore(pd.getCvssScore());
            score.setMetadata(metadata);
            detectionScoreRepo.save(score);
        }, () -> log.warn("Unknown score type '{}' for detection {}, skipping DetectionScore refresh",
            pd.getCvssVersion(), detectionId));
    }

    private void createScoreIfPresent(ParsedDetection pd, Long detectionId) {
        if (pd.getCvssScore() == null || pd.getCvssVersion() == null) return;
        scoreTypeRepo.findByTitle(pd.getCvssVersion()).ifPresentOrElse(type -> {
            com.martecyber.ares.detections.DetectionScore score = new com.martecyber.ares.detections.DetectionScore();
            score.setDetectionId(detectionId);
            score.setTypeId(type.getId());
            score.setScore(pd.getCvssScore());
            score.setDefault(true);
            score.setCreatedAt(OffsetDateTime.now());
            if (pd.getCvssVector() != null && !pd.getCvssVector().isBlank()) {
                try {
                    score.setMetadata(objectMapper.writeValueAsString(Map.of("vector", pd.getCvssVector())));
                } catch (Exception ignored) { /* best-effort, score itself still persists */ }
            }
            detectionScoreRepo.save(score);
        }, () -> log.warn("Unknown score type '{}' for detection {}, skipping DetectionScore",
            pd.getCvssVersion(), detectionId));
    }

    private static String stripNulBytes(String s) {
        if (s == null) return null;
        // Actual null character (Jackson may decode JSON \u0000 to this in field values)
        s = s.replace("\u0000", "");
        // Textual JSON escape sequence \\u0000 (literal backslash-u-0-0-0-0) that appears
        // in re-serialized JSON strings
        s = s.replace("\\u0000", "");
        return s;
    }
    /**
     * For every WEB_APPLICATION asset in the parse result, ensures a root WEB_ENDPOINT exists
     * and is linked via WEBAPP_ENDPOINT. Uses resolveOrCreate (type-aware DB lookup) so the
     * same URL identifier can be shared between WEB_APPLICATION and WEB_ENDPOINT safely.
     */
    private void ensureWebEndpoints(ParseResult parsed, Map<String, Long> assetIdByIdentifier,
                                     Map<String, Long> assetIdByIdentifierAndType,
                                     Long organizationId, Long projectId) {
        for (ParsedAsset pa : parsed.getAssets()) {
            if (!AssetType.WEB_APPLICATION.equals(pa.getType())) continue;
            // Must resolve by (identifier, type), not the bare identifier: a parser that also
            // emits its own root-path WEB_ENDPOINT sharing this exact identifier string (e.g.
            // BurpXMLParser, for a root-path issue) causes assetIdByIdentifier's "last write
            // wins" to silently point at the WEB_ENDPOINT's row instead of this WEB_APPLICATION's
            // — linkIfAbsent(endpointId, endpointId, WEBAPP_ENDPOINT) then fails type validation
            // ("Link type 'webapp_endpoint' is not valid from asset type 'web_endpoint'").
            Long webappId = assetIdByIdentifierAndType.get(assetKey(pa.getIdentifier(), AssetType.WEB_APPLICATION));
            if (webappId == null) webappId = assetIdByIdentifier.get(pa.getIdentifier());
            if (webappId == null) continue;
            try {
                // Root endpoint ("/") — some parsers (e.g. httpx) attach HTTP-probe-specific
                // facts (title/server/statusCode) directly to the WEB_APPLICATION; those
                // conceptually belong to the request that hit "/", so relocate them here.
                Map<String, Object> webAppMeta = pa.getMetadata();
                Object title      = webAppMeta.get("title");
                Object server     = webAppMeta.get("server");
                Object statusCode = webAppMeta.get("statusCode");

                Map<String, Object> rootMeta = new LinkedHashMap<>();
                rootMeta.put("path", "/");
                if (title != null) rootMeta.put("title", title);
                if (server != null) rootMeta.put("server", server);
                if (statusCode != null) rootMeta.put("statusCodes", List.of(statusCode));

                Long endpointId = assetHelper.resolveOrCreate(organizationId,
                    new ParsedAsset(pa.getIdentifier(), AssetType.WEB_ENDPOINT, rootMeta));
                assetHelper.linkIfAbsent(webappId, endpointId, AssetLinkType.WEBAPP_ENDPOINT);
                projectAssetRepo.linkIfAbsent(projectId, endpointId);

                if (title != null || server != null || statusCode != null) {
                    assetHelper.stripWebAppHttpProbeFields(webappId);
                }
            } catch (Exception e) {
                // Non-fatal, but must still be logged: this used to swallow the exception
                // completely (not even a result.addWarning), which is exactly how a failure here
                // can mark the surrounding @Transactional import rollback-only and only surface
                // minutes later as an unexplained UnexpectedRollbackException at commit time.
                log.warn("ensureWebEndpoints failed for webapp identifier '{}': {}", pa.getIdentifier(), e.getMessage(), e);
            }
            // NOTE: ensureWebApplicationTree disabled — needs async execution to avoid
            // exhausting the connection pool when multiple imports run concurrently.
        }
    }
}
