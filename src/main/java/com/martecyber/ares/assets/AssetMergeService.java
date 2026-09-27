package com.martecyber.ares.assets;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.affections.AffectionAssetRepository;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.detections.DetectionRepository;
import com.martecyber.ares.projects.ProjectAssetAccessRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AssetMergeService {

    private static final Set<String> FIRST_CLASS_KEYS = Set.of("identifier", "hostnames", "hostSubtype");

    private final AssetRepository assetRepo;
    private final AssetRelationshipRepository relRepo;
    private final DetectionRepository detectionRepo;
    private final AffectionAssetRepository affectionAssetRepo;
    private final ProjectAssetAccessRepository projectAccessRepo;
    private final AssetService assetService;
    private final ObjectMapper mapper;

    public AssetMergeService(AssetRepository assetRepo,
                              AssetRelationshipRepository relRepo,
                              DetectionRepository detectionRepo,
                              AffectionAssetRepository affectionAssetRepo,
                              ProjectAssetAccessRepository projectAccessRepo,
                              AssetService assetService,
                              ObjectMapper mapper) {
        this.assetRepo        = assetRepo;
        this.relRepo          = relRepo;
        this.detectionRepo    = detectionRepo;
        this.affectionAssetRepo = affectionAssetRepo;
        this.projectAccessRepo  = projectAccessRepo;
        this.assetService     = assetService;
        this.mapper           = mapper;
    }

    /** One field's chosen resolution. {@code mode}: "source" | "target" | "merge" | "custom".
     *  {@code customValue} is only read when mode == "custom" (a String or a List of strings). */
    public record FieldResolution(String key, String mode, Object customValue) {}

    public record MergeRequest(Long targetId, List<FieldResolution> fields) {}

    @Transactional
    public Asset merge(Long sourceId, MergeRequest req) {
        Asset source = assetRepo.findById(sourceId)
            .orElseThrow(() -> NotFoundException.of("asset", sourceId));
        Asset target = assetRepo.findById(req.targetId())
            .orElseThrow(() -> NotFoundException.of("asset", req.targetId()));

        if (sourceId.equals(req.targetId()))
            throw new IllegalArgumentException("Source and target must be different assets");
        if (!source.getType().equals(target.getType()))
            throw new IllegalArgumentException("Cannot merge assets of different types ('"
                + source.getType() + "' vs '" + target.getType() + "')");
        if (!source.getOrganizationId().equals(target.getOrganizationId()))
            throw new IllegalArgumentException("Cannot merge assets from different organizations");

        // ── 1. Transfer asset relationships ─────────────────────────────────
        transferRelationships(sourceId, target.getId());

        // ── 2. Transfer detections ───────────────────────────────────────────
        detectionRepo.reassignAsset(sourceId, target.getId());

        // ── 3. Transfer affection_asset links ────────────────────────────────
        affectionAssetRepo.copyToNewAsset(sourceId, target.getId());
        affectionAssetRepo.deleteByAssetId(sourceId);

        // ── 4. Transfer project_asset_access ────────────────────────────────
        // Add target to every project that contained source, then remove source
        // explicitly (no CASCADE assumed on the FK constraint).
        projectAccessRepo.findByAssetId(sourceId).forEach(pa -> {
            projectAccessRepo.linkIfAbsent(pa.getProjectId(), target.getId());
            projectAccessRepo.deleteByProjectIdAndAssetId(pa.getProjectId(), sourceId);
        });

        // ── 5. Apply per-field resolutions ──────────────────────────────────
        Map<String, Object> sourceMeta = parseMeta(source.getMetadata());
        Map<String, Object> targetMeta = parseMeta(target.getMetadata());
        Set<String> resolvedMetaKeys = new HashSet<>();

        String identifierMode = "target";
        boolean baseUrlsTouched = false;
        List<String> resolvedBaseUrls = null;

        List<FieldResolution> fields = req.fields() != null ? req.fields() : List.of();
        for (FieldResolution fr : fields) {
            String key = fr.key();
            String mode = fr.mode() != null ? fr.mode() : "target";

            if ("identifier".equals(key)) {
                identifierMode = mode;
                target.setIdentifier(switch (mode) {
                    case "source" -> source.getIdentifier();
                    case "custom" -> String.valueOf(fr.customValue());
                    default        -> target.getIdentifier();
                });
                continue;
            }
            if ("hostnames".equals(key)) {
                target.setHostnames(switch (mode) {
                    case "source" -> new ArrayList<>(source.getHostnames());
                    case "merge"  -> mergeStringLists(target.getHostnames(), source.getHostnames());
                    case "custom" -> toStringList(fr.customValue());
                    default        -> new ArrayList<>(target.getHostnames());
                });
                continue;
            }
            if ("hostSubtype".equals(key)) {
                String value = switch (mode) {
                    case "source" -> source.getHostSubtype();
                    case "custom" -> String.valueOf(fr.customValue());
                    default        -> target.getHostSubtype();
                };
                if (value != null && HostSubtype.ALL.contains(value)) target.setHostSubtype(value);
                continue;
            }

            // Generic metadata key
            resolvedMetaKeys.add(key);
            Object sourceVal = sourceMeta.get(key);
            Object targetVal = targetMeta.get(key);
            boolean listShaped = sourceVal instanceof List || targetVal instanceof List
                || fr.customValue() instanceof List;

            Object resolved;
            if (listShaped) {
                resolved = switch (mode) {
                    case "source" -> toStringList(sourceVal);
                    case "merge"  -> mergeStringLists(toStringList(targetVal), toStringList(sourceVal));
                    case "custom" -> toStringList(fr.customValue());
                    default        -> toStringList(targetVal);
                };
            } else {
                resolved = switch (mode) {
                    case "source" -> sourceVal;
                    case "custom" -> fr.customValue();
                    default        -> targetVal;
                };
            }
            if (resolved == null) targetMeta.remove(key);
            else targetMeta.put(key, resolved);

            if ("baseUrls".equals(key) && AssetType.WEB_APPLICATION.equals(target.getType())) {
                baseUrlsTouched = true;
                //noinspection unchecked
                resolvedBaseUrls = (List<String>) resolved;
            }
        }

        // Residual metadata keys with no explicit resolution: auto-merge (union, target wins).
        sourceMeta.forEach((k, v) -> {
            if (!resolvedMetaKeys.contains(k) && !FIRST_CLASS_KEYS.contains(k)) targetMeta.putIfAbsent(k, v);
        });
        try {
            target.setMetadata(mapper.writeValueAsString(targetMeta));
        } catch (Exception ignored) {
            // keep target's pre-merge metadata rather than fail the whole merge
        }

        // HOST assets: Name either stays pinned to the resolved identifier, or is
        // recomputed from the (possibly merged) hostnames list.
        if (AssetType.HOST.equals(target.getType())) {
            if ("source".equals(identifierMode) || "custom".equals(identifierMode)) {
                target.setNameOverride(true);
            } else if (!target.isNameOverride()) {
                assetService.recomputeHostName(target);
            }
        }

        // web_application: Name always follows Base URLs, same as manual edits.
        if (baseUrlsTouched && resolvedBaseUrls != null && !resolvedBaseUrls.isEmpty()) {
            target.setIdentifier(resolvedBaseUrls.get(0));
        }

        target.setUpdatedAt(OffsetDateTime.now());
        assetRepo.save(target);

        // ── 6. Delete source ─────────────────────────────────────────────────
        assetRepo.deleteById(sourceId);

        return target;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void transferRelationships(Long sourceId, Long targetId) {
        // Pre-load target's existing relationships into sets for O(1) dedup.
        // This avoids N+1 queries and ensures correctness even if target already
        // shares some relationships with source.
        java.util.Set<String> existingOut = relRepo.findByFromAssetId(targetId).stream()
            .map(r -> r.getToAssetId() + ":" + r.getType())
            .collect(java.util.stream.Collectors.toCollection(java.util.HashSet::new));
        java.util.Set<String> existingIn = relRepo.findByToAssetId(targetId).stream()
            .map(r -> r.getFromAssetId() + ":" + r.getType())
            .collect(java.util.stream.Collectors.toCollection(java.util.HashSet::new));

        // Outgoing: source → X  →  target → X  (skip source ↔ target)
        for (AssetRelationship r : relRepo.findByFromAssetId(sourceId)) {
            relRepo.delete(r);
            if (r.getToAssetId().equals(targetId)) continue;
            String key = r.getToAssetId() + ":" + r.getType();
            if (existingOut.add(key)) // add() returns false if already present
                relRepo.save(buildRel(targetId, r.getToAssetId(), r.getType(), r.isDirectional(), r.getCreatedAt()));
        }

        // Incoming: X → source  →  X → target  (skip target ↔ source)
        for (AssetRelationship r : relRepo.findByToAssetId(sourceId)) {
            relRepo.delete(r);
            if (r.getFromAssetId().equals(targetId)) continue;
            String key = r.getFromAssetId() + ":" + r.getType();
            if (existingIn.add(key))
                relRepo.save(buildRel(r.getFromAssetId(), targetId, r.getType(), r.isDirectional(), r.getCreatedAt()));
        }
    }

    private Map<String, Object> parseMeta(String raw) {
        if (isBlank(raw)) return new LinkedHashMap<>();
        try {
            return new LinkedHashMap<>(mapper.readValue(raw, new TypeReference<Map<String, Object>>() {}));
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> toStringList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) if (o != null) out.add(String.valueOf(o));
        }
        return out;
    }

    /** Case-insensitive union, preserving target's existing order and appending source's new entries. */
    private static List<String> mergeStringLists(List<String> target, List<String> source) {
        List<String> result = new ArrayList<>(target);
        Set<String> seen = new HashSet<>();
        for (String h : result) seen.add(h.toLowerCase());
        for (String h : source) {
            if (h == null || h.isBlank()) continue;
            if (seen.add(h.toLowerCase())) result.add(h);
        }
        return result;
    }

    private static AssetRelationship buildRel(Long from, Long to, String type, boolean directional, OffsetDateTime at) {
        AssetRelationship r = new AssetRelationship();
        r.setFromAssetId(from); r.setToAssetId(to); r.setType(type);
        r.setDirectional(directional); r.setCreatedAt(at != null ? at : OffsetDateTime.now());
        return r;
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank() || s.equals("{}") || s.equals("null"); }
}
