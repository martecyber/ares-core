package com.martecyber.ares.imports;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.assets.Asset;
import com.martecyber.ares.assets.AssetExternalId;
import com.martecyber.ares.assets.AssetExternalIdRepository;
import com.martecyber.ares.assets.AssetLinkType;
import com.martecyber.ares.assets.AssetRelationship;
import com.martecyber.ares.assets.AssetRelationshipRepository;
import com.martecyber.ares.assets.AssetRepository;
import com.martecyber.ares.assets.AssetService;
import com.martecyber.ares.assets.AssetType;
import com.martecyber.ares.projects.ProjectAssetAccessRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Runs each asset find-or-create in its own transaction so that a constraint
 * violation (duplicate code/identifier) rolls back only that sub-transaction
 * instead of poisoning the parent import transaction.
 */
@Service
public class AssetImportHelper {

    private static final Logger log = LoggerFactory.getLogger(AssetImportHelper.class);
    private static final Pattern IP_PATTERN = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    /**
     * Reserved metadata key carrying a hint list of hostnames observed for a HOST
     * ParsedAsset. Every producer (ScannerParserUtils, NmapXMLParser, Tenable parsers)
     * writes it instead of baking the hostname into the HOST's parse-time identifier —
     * {@link #resolveOrCreate} merges the hints into the resolved host's {@code hostnames}
     * column and strips the key before persisting metadata.
     */
    public static final String HOSTNAME_HINTS_KEY = AssetMetadataKeys.HOSTNAME_HINTS_KEY;

    /**
     * Reserved metadata key pair carrying a source tool's own stable host identifier (Tenable
     * asset.id, Greenbone/GVM asset_id, …) for a HOST ParsedAsset. When present, {@link
     * #resolveOrCreateHost} resolves via {@link AssetExternalIdRepository} first — trusting it
     * over the IP/interface chain, since the tool's own id survives an active-IP change (DHCP
     * reassignment, agent vs. network scan reporting a different NIC) that would otherwise make
     * {@link #resolveOrCreateHost}'s IP-based lookup miss and create a duplicate host. Both keys
     * are stripped before persisting metadata, same as {@link #HOSTNAME_HINTS_KEY}.
     */
    public static final String EXTERNAL_ID_TOOL_KEY = AssetMetadataKeys.EXTERNAL_ID_TOOL_KEY;
    public static final String EXTERNAL_ID_VALUE_KEY = AssetMetadataKeys.EXTERNAL_ID_VALUE_KEY;

    private final AssetRepository assetRepo;
    private final AssetRelationshipRepository relRepo;
    private final AssetService assetService;
    private final ProjectAssetAccessRepository projectAssetRepo;
    private final ScanImportChangeRepository changeRepo;
    private final AssetExternalIdRepository externalIdRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AssetImportHelper(AssetRepository assetRepo, AssetRelationshipRepository relRepo,
                             AssetService assetService, ProjectAssetAccessRepository projectAssetRepo,
                             ScanImportChangeRepository changeRepo, AssetExternalIdRepository externalIdRepo) {
        this.assetRepo = assetRepo;
        this.relRepo = relRepo;
        this.assetService = assetService;
        this.projectAssetRepo = projectAssetRepo;
        this.changeRepo = changeRepo;
        this.externalIdRepo = externalIdRepo;
    }

    /**
     * Carries the current {@code scan_import.id} through asset resolution without threading it
     * through every method signature in this class (many of which run in their own REQUIRES_NEW
     * sub-transaction, but stay on the same thread as the outer import call). Set/cleared by
     * {@code ImportService} around a single import run; left unset for any caller outside an
     * import (e.g. Caido ingestion, scope-derived assets) — those simply get no rollback tracking,
     * which is correct since they have no {@code scan_import} row to attach to.
     */
    private static final ThreadLocal<Long> CURRENT_SCAN_IMPORT_ID = new ThreadLocal<>();

    public static void setCurrentScanImportId(Long scanImportId) { CURRENT_SCAN_IMPORT_ID.set(scanImportId); }
    public static void clearCurrentScanImportId() { CURRENT_SCAN_IMPORT_ID.remove(); }

    private void recordChange(String entityType, Long entityId, String action, Object prevValuesSnapshot) {
        Long scanImportId = CURRENT_SCAN_IMPORT_ID.get();
        if (scanImportId == null) return;
        ScanImportChange c = new ScanImportChange();
        c.setScanImportId(scanImportId);
        c.setEntityType(entityType);
        c.setEntityId(entityId);
        c.setAction(action);
        if (prevValuesSnapshot != null) {
            try { c.setPrevValues(objectMapper.writeValueAsString(prevValuesSnapshot)); }
            catch (Exception e) { log.warn("Failed to snapshot prev values for {} {}: {}", entityType, entityId, e.getMessage()); }
        }
        c.setCreatedAt(OffsetDateTime.now());
        changeRepo.save(c);
    }

    private Map<String, Object> assetSnapshot(Asset a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("identifier", a.getIdentifier());
        m.put("hostnames", new ArrayList<>(a.getHostnames()));
        m.put("metadata", a.getMetadata() != null ? a.getMetadata() : "{}");
        return m;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long resolveOrCreate(Long organizationId, ParsedAsset pa) {
        if (AssetType.HOST.equals(pa.getType())) {
            return resolveOrCreateHost(organizationId, pa);
        }

        // Fast path: asset already exists by (org, type, identifier)
        java.util.Optional<Asset> existing =
            assetRepo.findByOrganizationIdAndTypeAndIdentifier(organizationId, pa.getType(), pa.getIdentifier());
        if (existing.isPresent()) {
            // For web_endpoint assets, merge observed query params + request-variable
            // fields (status codes, content types, etc.) into existing metadata
            if (AssetType.WEB_ENDPOINT.equals(pa.getType())) {
                Map<String, Object> newParams = getParams(pa.getMetadata());
                if (!newParams.isEmpty()) mergeEndpointParams(existing.get(), newParams);
                mergeEndpointArrayFields(existing.get(), pa.getMetadata());
            }
            return existing.get().getId();
        }

        // Create the asset
        return createAsset(organizationId, pa);
    }

    /**
     * HOST identity never trusts the parse-time identifier as the real identity — every
     * producer emits it as "host-{ip}" (an internal join key, see ScannerParserUtils and the
     * NmapXMLParser/Tenable parsers). Instead: resolve the INTERFACE for that ip first, and if
     * a HOST is already linked to it via HOST_INTERFACE, reuse that host regardless of what the
     * current scan's hostname hint says — this is what keeps host identity stable across a
     * hostname change (the reported duplicate-host bug) and also what previously only applied
     * narrowly to "host-{ip}" virtual placeholders (superseding the old upgradeVirtualHost()
     * one-directional rename special case, which is no longer needed).
     *
     * Any hostname hints carried on the ParsedAsset (key {@link #HOSTNAME_HINTS_KEY}) are
     * appended to the resolved host's {@code hostnames} list (if not already present), and the
     * host's Name is recomputed via {@link AssetService#recomputeHostName} unless the user has
     * pinned it (nameOverride=true).
     */
    private Long resolveOrCreateHost(Long organizationId, ParsedAsset pa) {
        List<String> hints = hostnameHints(pa.getMetadata());
        String ip = pa.getIdentifier().startsWith("host-") ? pa.getIdentifier().substring(5) : null;
        String extTool = externalIdTool(pa.getMetadata());
        String extValue = externalIdValue(pa.getMetadata());

        // External-id resolution takes priority over the IP/interface chain below: the source
        // tool's own host id is stable across an active-IP change, which is exactly the case
        // that made the IP-based lookup create a duplicate host (see class javadoc on the
        // EXTERNAL_ID_* keys).
        if (extTool != null && extValue != null) {
            java.util.Optional<Asset> byExternalId = externalIdRepo.findByToolAndExternalId(extTool, extValue)
                .flatMap(mapping -> assetRepo.findById(mapping.getAssetId()));
            if (byExternalId.isPresent()) {
                if (!hints.isEmpty()) appendHostnames(byExternalId.get(), hints);
                return byExternalId.get().getId();
            }
        }

        if (ip != null) {
            String ifaceIdentifier = "iface-" + ip;
            java.util.Optional<Asset> iface = assetRepo
                .findByOrganizationIdAndTypeAndIdentifier(organizationId, AssetType.INTERFACE, ifaceIdentifier);
            if (iface.isPresent()) {
                java.util.Optional<Asset> existingHost = relRepo
                    .findByToAssetIdAndType(iface.get().getId(), AssetLinkType.HOST_INTERFACE)
                    .stream()
                    .map(AssetRelationship::getFromAssetId)
                    .findFirst()
                    .flatMap(assetRepo::findById);
                if (existingHost.isPresent()) {
                    if (!hints.isEmpty()) appendHostnames(existingHost.get(), hints);
                    return recordExternalIdIfAbsent(existingHost.get().getId(), extTool, extValue);
                }
            }
        }

        // Fast path for a HOST resolved by its own (non "host-{ip}") identifier — e.g. a
        // manually-created host, or a caller passing a real identifier directly.
        java.util.Optional<Asset> existing =
            assetRepo.findByOrganizationIdAndTypeAndIdentifier(organizationId, AssetType.HOST, pa.getIdentifier());
        if (existing.isPresent()) {
            if (!hints.isEmpty()) appendHostnames(existing.get(), hints);
            return recordExternalIdIfAbsent(existing.get().getId(), extTool, extValue);
        }

        // No existing host found — create one, seeding hostnames/Name via the fallback rule.
        Asset a = new Asset();
        a.setOrganizationId(organizationId);
        a.setType(AssetType.HOST);
        a.setHostnames(new ArrayList<>(hints));
        a.setIdentifier(!hints.isEmpty() ? hints.get(0) : (ip != null ? "host-" + ip : pa.getIdentifier()));
        a.setCode(assetService.generateCode(organizationId, AssetType.HOST));
        try {
            a.setMetadata(objectMapper.writeValueAsString(stripExternalIdKeys(stripHostnameHints(pa.getMetadata()))));
        } catch (Exception e) {
            a.setMetadata("{}");
        }
        OffsetDateTime now = OffsetDateTime.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        Asset saved = assetRepo.save(a);
        recordChange("asset", saved.getId(), "created", null);
        return recordExternalIdIfAbsent(saved.getId(), extTool, extValue);
    }

    /** Upserts the {@code (tool, externalId) -> hostId} mapping used by the external-id fast
     *  path above, when both were provided on the ParsedAsset and no mapping exists yet.
     *  No-op (just returns hostId) when either is null, or a mapping already exists — this is
     *  only reached on branches that didn't already resolve via that mapping, so it never
     *  overwrites an existing row pointing at a stale/merged asset. */
    private Long recordExternalIdIfAbsent(Long hostId, String tool, String externalId) {
        if (tool == null || externalId == null) return hostId;
        if (externalIdRepo.findByToolAndExternalId(tool, externalId).isPresent()) return hostId;
        AssetExternalId mapping = new AssetExternalId();
        mapping.setAssetId(hostId);
        mapping.setTool(tool);
        mapping.setExternalId(externalId);
        mapping.setCreatedAt(OffsetDateTime.now());
        externalIdRepo.save(mapping);
        return hostId;
    }

    /** Appends any new (case-insensitive) hostnames to an existing host and recomputes its Name. */
    private void appendHostnames(Asset host, List<String> hints) {
        Map<String, Object> before = assetSnapshot(host);
        List<String> names = new ArrayList<>(host.getHostnames());
        Set<String> existingLower = new java.util.HashSet<>();
        for (String n : names) existingLower.add(n.toLowerCase());
        boolean changed = false;
        for (String hint : hints) {
            if (hint == null || hint.isBlank()) continue;
            String trimmed = hint.trim();
            if (existingLower.add(trimmed.toLowerCase())) {
                names.add(trimmed);
                changed = true;
            }
        }
        if (!changed) return;
        host.setHostnames(names);
        assetService.recomputeHostName(host);
        host.setUpdatedAt(OffsetDateTime.now());
        assetRepo.save(host);
        recordChange("asset", host.getId(), "updated", before);
    }

    @SuppressWarnings("unchecked")
    private List<String> hostnameHints(Map<String, Object> meta) {
        if (meta == null) return List.of();
        Object v = meta.get(HOSTNAME_HINTS_KEY);
        if (v instanceof List<?> list) return list.stream().map(String::valueOf).toList();
        return List.of();
    }

    private Map<String, Object> stripHostnameHints(Map<String, Object> meta) {
        if (meta == null || !meta.containsKey(HOSTNAME_HINTS_KEY)) return meta;
        Map<String, Object> copy = new LinkedHashMap<>(meta);
        copy.remove(HOSTNAME_HINTS_KEY);
        return copy;
    }

    private String externalIdTool(Map<String, Object> meta) {
        if (meta == null) return null;
        Object v = meta.get(EXTERNAL_ID_TOOL_KEY);
        return v != null ? String.valueOf(v) : null;
    }

    private String externalIdValue(Map<String, Object> meta) {
        if (meta == null) return null;
        Object v = meta.get(EXTERNAL_ID_VALUE_KEY);
        return v != null ? String.valueOf(v) : null;
    }

    private Map<String, Object> stripExternalIdKeys(Map<String, Object> meta) {
        if (meta == null || (!meta.containsKey(EXTERNAL_ID_TOOL_KEY) && !meta.containsKey(EXTERNAL_ID_VALUE_KEY))) return meta;
        Map<String, Object> copy = new LinkedHashMap<>(meta);
        copy.remove(EXTERNAL_ID_TOOL_KEY);
        copy.remove(EXTERNAL_ID_VALUE_KEY);
        return copy;
    }

    private Long createAsset(Long organizationId, ParsedAsset pa) {
        Asset a = new Asset();
        a.setOrganizationId(organizationId);
        a.setIdentifier(pa.getIdentifier());
        a.setType(pa.getType());
        a.setCode(assetService.generateCode(organizationId, pa.getType()));
        try {
            a.setMetadata(objectMapper.writeValueAsString(pa.getMetadata()));
        } catch (Exception e) {
            a.setMetadata("{}");
        }
        OffsetDateTime now = OffsetDateTime.now();
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        Asset saved = assetRepo.save(a);
        recordChange("asset", saved.getId(), "created", null);
        if (AssetType.IP.equals(pa.getType())) {
            assetService.autoLinkIpToNetwork(organizationId, saved);
        } else if (AssetType.DOMAIN.equals(pa.getType())) {
            assetService.autoLinkSubdomain(organizationId, saved);
        }
        return saved.getId();
    }

    /**
     * Idempotent link creation. Skips if the relationship already exists.
     * Runs in the caller's transaction (no REQUIRES_NEW) to avoid too many sub-transactions.
     *
     * Validates the (fromType, linkType, toType) triple against {@link AssetLinkType#validate}
     * before persisting — this is the enforcement point for every import/scan tool, not just
     * the manual "add relationship" UI/API, so a parser bug can never silently create a
     * relationship between asset types that don't support it.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void linkIfAbsent(Long fromId, Long toId, String linkType) {
        Asset from = assetRepo.findById(fromId)
            .orElseThrow(() -> new IllegalArgumentException("Asset not found: " + fromId));
        Asset to = assetRepo.findById(toId)
            .orElseThrow(() -> new IllegalArgumentException("Asset not found: " + toId));
        AssetLinkType.validate(from.getType(), linkType, to.getType());

        boolean exists = relRepo.findByFromAssetId(fromId).stream()
            .anyMatch(r -> r.getToAssetId().equals(toId) && linkType.equals(r.getType()));
        if (exists) return;
        AssetRelationship r = new AssetRelationship();
        r.setFromAssetId(fromId);
        r.setToAssetId(toId);
        r.setType(linkType);
        r.setDirectional(true);
        r.setCreatedAt(OffsetDateTime.now());
        relRepo.save(r);
    }

    /**
     * Exclusive link creation: the target asset can have at most one incoming link of
     * this type (e.g. an interface belongs to exactly one host, an IP to one interface).
     *
     * Removes any existing link of the same type pointing TO toId from a DIFFERENT
     * fromId before creating the new link. This handles IP/interface reassignment
     * when a network address moves from one host to another.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public void linkExclusive(Long fromId, Long toId, String linkType) {
        relRepo.findByToAssetIdAndType(toId, linkType).stream()
            .filter(r -> !r.getFromAssetId().equals(fromId))
            .forEach(r -> relRepo.deleteByFromAssetIdAndToAssetIdAndType(
                r.getFromAssetId(), toId, linkType));
        linkIfAbsent(fromId, toId, linkType);
    }

    /**
     * Ensures the full Host → Interface → IP chain exists for a scanned IP. The HOST is always
     * resolved via {@link #resolveOrCreateHost} using the "host-{ip}" join key (any hostname is
     * passed as a hint, merged into the resolved host's {@code hostnames} rather than driving
     * its identifier) — this supersedes the old virtual-host-rename special case, since HOST
     * resolution now generally reuses whichever host already owns the interface for this IP.
     *
     * @param orgId    organization ID
     * @param ip       scanned IP address (e.g., "192.168.1.50")
     * @param hostname best-known hostname; null contributes no hint
     * @param mac      MAC address for the interface identifier; null falls back to "iface-{ip}"
     * @param hostMeta optional extra metadata for the host asset
     * @return the interface asset ID (attach services to this)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long resolveOrCreateHostChain(Long orgId, String ip, String hostname, String mac,
                                          Map<String, Object> hostMeta) {
        String hostIdentifier  = "host-" + ip;
        String ifaceIdentifier = (mac != null && !mac.isBlank()) ? mac : "iface-" + ip;

        Map<String, Object> hostMetaWithHint = new LinkedHashMap<>(hostMeta != null ? hostMeta : Map.of());
        if (hostname != null && !hostname.isBlank()) {
            hostMetaWithHint.put(HOSTNAME_HINTS_KEY, List.of(hostname));
        }

        // 1. Host
        Long hostId = resolveOrCreate(orgId, new ParsedAsset(hostIdentifier, AssetType.HOST, hostMetaWithHint));

        // 2. Interface
        Long ifaceId = resolveOrCreate(orgId, new ParsedAsset(ifaceIdentifier, AssetType.INTERFACE,
            Map.of("host", hostIdentifier)));

        // 3. IP
        Long ipId = resolveOrCreate(orgId, new ParsedAsset(ip, AssetType.IP, Map.of()));

        // 4. Links (idempotent)
        linkIfAbsent(hostId, ifaceId, AssetLinkType.HOST_INTERFACE);
        linkIfAbsent(ifaceId, ipId,   AssetLinkType.INTERFACE_IP);

        return ifaceId;
    }

    /**
     * Creates a service asset and links it to the given interface.
     *
     * @param orgId       organization ID
     * @param ifaceId     interface asset ID (result of {@link #resolveOrCreateHostChain})
     * @param ip          IP used to build the service identifier
     * @param port        TCP/UDP port number
     * @param proto       protocol string ("tcp" or "udp")
     * @param serviceMeta metadata (product, version, service name, etc.)
     * @return the service asset ID
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public Long resolveOrCreateService(Long orgId, Long ifaceId, String ip, int port, String proto,
                                        Map<String, Object> serviceMeta) {
        String svcIdentifier = ip + ":" + port + "/" + proto.toLowerCase();
        Long svcId = resolveOrCreate(orgId, new ParsedAsset(svcIdentifier, AssetType.SERVICE,
            serviceMeta != null ? serviceMeta : Map.of()));
        linkIfAbsent(ifaceId, svcId, AssetLinkType.INTERFACE_SERVICE);
        return svcId;
    }

    /** Returns the params map from a ParsedAsset's metadata, or empty map. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> getParams(Map<String, Object> meta) {
        Object p = meta.get("params");
        return (p instanceof Map) ? (Map<String, Object>) p : Map.of();
    }

    /**
     * Merges new query-param observations into an existing web_endpoint asset's metadata.
     * The params field has structure: {@code {"paramName": ["val1", "val2", …]}}.
     */
    @SuppressWarnings("unchecked")
    private void mergeEndpointParams(Asset asset, Map<String, Object> newParams) {
        Map<String, Object> before = assetSnapshot(asset);
        try {
            Map<String, Object> existingMeta = objectMapper.readValue(
                asset.getMetadata() != null ? asset.getMetadata() : "{}", Map.class);
            Object rawParams = existingMeta.get("params");
            Map<String, List<String>> params = (rawParams instanceof Map)
                ? new LinkedHashMap<>((Map<String, List<String>>) rawParams)
                : new LinkedHashMap<>();
            newParams.forEach((k, v) -> {
                List<String> values = params.computeIfAbsent(k, x -> new ArrayList<>());
                List<?> incoming = (v instanceof List) ? (List<?>) v : List.of(v.toString());
                incoming.forEach(val -> { if (!values.contains(val.toString())) values.add(val.toString()); });
            });
            existingMeta.put("params", params);
            asset.setMetadata(objectMapper.writeValueAsString(existingMeta));
            asset.setUpdatedAt(OffsetDateTime.now());
            assetRepo.save(asset);
            recordChange("asset", asset.getId(), "updated", before);
        } catch (Exception e) {
            log.warn("Failed to merge endpoint params for asset {}: {}", asset.getId(), e.getMessage());
        }
    }

    /** web_endpoint metadata keys that hold the distinct set of values observed across all
     *  requests/imports for that endpoint (rather than a single current value), e.g. an
     *  endpoint can return 200 on one crawl and 404 on a later one — both are kept. */
    private static final Set<String> ENDPOINT_ARRAY_FIELDS = Set.of(
        "statusCodes", "contentTypes", "lengths", "wordCounts", "redirectLocations", "sources");

    /**
     * Merges newly observed values for {@link #ENDPOINT_ARRAY_FIELDS} into an existing
     * web_endpoint's metadata — each field accumulates distinct values across imports
     * instead of being overwritten, mirroring {@link #mergeEndpointParams}.
     */
    @SuppressWarnings("unchecked")
    private void mergeEndpointArrayFields(Asset asset, Map<String, Object> incomingMeta) {
        boolean hasAny = ENDPOINT_ARRAY_FIELDS.stream().anyMatch(incomingMeta::containsKey);
        if (!hasAny) return;
        Map<String, Object> before = assetSnapshot(asset);
        try {
            Map<String, Object> existingMeta = objectMapper.readValue(
                asset.getMetadata() != null ? asset.getMetadata() : "{}", Map.class);
            boolean changed = false;
            for (String field : ENDPOINT_ARRAY_FIELDS) {
                Object incomingRaw = incomingMeta.get(field);
                if (incomingRaw == null) continue;
                List<?> incoming = (incomingRaw instanceof List) ? (List<?>) incomingRaw : List.of(incomingRaw);
                Object existingRaw = existingMeta.get(field);
                List<Object> values = (existingRaw instanceof List)
                    ? new ArrayList<>((List<Object>) existingRaw) : new ArrayList<>();
                for (Object v : incoming) {
                    String vs = String.valueOf(v);
                    if (values.stream().noneMatch(ev -> String.valueOf(ev).equals(vs))) {
                        values.add(v);
                        changed = true;
                    }
                }
                existingMeta.put(field, values);
            }
            if (!changed) return;
            asset.setMetadata(objectMapper.writeValueAsString(existingMeta));
            asset.setUpdatedAt(OffsetDateTime.now());
            assetRepo.save(asset);
            recordChange("asset", asset.getId(), "updated", before);
        } catch (Exception e) {
            log.warn("Failed to merge endpoint array fields for asset {}: {}", asset.getId(), e.getMessage());
        }
    }

    /**
     * Removes HTTP-probe-specific keys that some parsers (e.g. httpx) write directly onto a
     * WEB_APPLICATION's metadata — they belong on the root WEB_ENDPOINT instead (see
     * {@code ImportService.ensureWebEndpoints}).
     */
    public void stripWebAppHttpProbeFields(Long webAppAssetId) {
        assetRepo.findById(webAppAssetId).ifPresent(a -> {
            Map<String, Object> before = assetSnapshot(a);
            try {
                Map<String, Object> meta = objectMapper.readValue(
                    a.getMetadata() != null ? a.getMetadata() : "{}", Map.class);
                boolean changed = meta.remove("title") != null;
                changed |= meta.remove("server") != null;
                changed |= meta.remove("statusCode") != null;
                if (!changed) return;
                a.setMetadata(objectMapper.writeValueAsString(meta));
                a.setUpdatedAt(OffsetDateTime.now());
                assetRepo.save(a);
                recordChange("asset", a.getId(), "updated", before);
            } catch (Exception ignored) { /* best-effort cleanup */ }
        });
    }

    /**
     * Ensures the infrastructure asset tree exists for a web_application asset.
     * <p>
     * For IP-based URLs (e.g. {@code https://10.0.0.1/}):
     * <ol>
     *   <li>Creates HOST → INTERFACE → IP chain (idempotent).</li>
     *   <li>Creates the SERVICE for the given port and links INTERFACE → SERVICE.</li>
     *   <li>Links WEB_APPLICATION → SERVICE via {@code webapp_service}.</li>
     * </ol>
     * For domain-based URLs (e.g. {@code https://api.example.com/}):
     * <ol>
     *   <li>Creates the DOMAIN and links WEB_APPLICATION → DOMAIN via {@code webapp_domain}.</li>
     *   <li>If a {@code domain_a} link to a known IP already exists, creates the SERVICE
     *       on that IP's interface and links WEB_APPLICATION → SERVICE.</li>
     * </ol>
     * All created assets are also linked to the project. Non-fatal: errors are logged and swallowed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureWebApplicationTree(Long orgId, Long projectId, Long webAppId, String webAppUrl) {
        try {
            URI uri = new URI(webAppUrl);
            String host = uri.getHost();
            if (host == null || host.isBlank()) return;
            String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase() : "http";
            int port = uri.getPort() == -1 ? ("https".equals(scheme) ? 443 : 80) : uri.getPort();

            if (IP_PATTERN.matcher(host.trim()).matches()) {
                // IP-based: build HOST→INTERFACE→IP chain, then SERVICE
                Long ifaceId = resolveOrCreateHostChain(orgId, host, null, null, Map.of());
                Long svcId   = resolveOrCreateService(orgId, ifaceId, host, port, "tcp",
                    Map.of("port", port, "protocol", "tcp", "service", scheme));
                linkIfAbsent(webAppId, svcId, AssetLinkType.WEBAPP_SERVICE);
                // Link all chain assets to the project
                relRepo.findByToAssetIdAndType(ifaceId, AssetLinkType.HOST_INTERFACE)
                    .forEach(r -> projectAssetRepo.linkIfAbsent(projectId, r.getFromAssetId()));
                relRepo.findByFromAssetIdAndType(ifaceId, AssetLinkType.INTERFACE_IP)
                    .forEach(r -> projectAssetRepo.linkIfAbsent(projectId, r.getToAssetId()));
                projectAssetRepo.linkIfAbsent(projectId, ifaceId);
                projectAssetRepo.linkIfAbsent(projectId, svcId);
            } else {
                // Domain-based: create/link DOMAIN
                Long domainId = resolveOrCreate(orgId, new ParsedAsset(host, AssetType.DOMAIN, Map.of()));
                linkIfAbsent(webAppId, domainId, AssetLinkType.WEBAPP_DOMAIN);
                projectAssetRepo.linkIfAbsent(projectId, domainId);
                // If we already know the IP for this domain, wire the service
                final int finalPort = port;
                final String finalScheme = scheme;
                relRepo.findByFromAssetIdAndType(domainId, AssetLinkType.DOMAIN_A)
                    .forEach(domainALink -> assetRepo.findById(domainALink.getToAssetId()).ifPresent(ipAsset -> {
                        relRepo.findByToAssetIdAndType(ipAsset.getId(), AssetLinkType.INTERFACE_IP)
                            .stream().findFirst().ifPresent(ifaceLink -> {
                                Long svcId = resolveOrCreateService(orgId, ifaceLink.getFromAssetId(),
                                    ipAsset.getIdentifier(), finalPort, "tcp",
                                    Map.of("port", finalPort, "protocol", "tcp", "service", finalScheme));
                                linkIfAbsent(webAppId, svcId, AssetLinkType.WEBAPP_SERVICE);
                                projectAssetRepo.linkIfAbsent(projectId, svcId);
                            });
                    }));
            }
        } catch (Exception e) {
            log.warn("ensureWebApplicationTree failed for webapp {} url={}: {}", webAppId, webAppUrl, e.getMessage());
        }
    }

    private static boolean isIp(String s) {
        return s != null && IP_PATTERN.matcher(s.trim()).matches();
    }

}
