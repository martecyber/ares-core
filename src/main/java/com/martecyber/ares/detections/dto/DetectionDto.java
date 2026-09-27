package com.martecyber.ares.detections.dto;

import com.martecyber.ares.detections.Detection;
import com.martecyber.ares.detections.DetectionScore;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record DetectionDto(
    Long id,
    Long projectId,
    Long assetId,
    String assetCode,
    String assetIdentifier,
    String severity,
    String status,
    String title,
    String description,
    String rawData,
    String sourceType,
    String sourceTemplateId,
    int occurrenceCount,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt,
    OffsetDateTime lastSeen,
    List<ReferenceRef> references,
    List<ScoreRef> scores,
    /**
     * Assets the operator considers affected by this detection. Independent of
     * {@link #assetId} (the single asset where the scanner saw the issue).
     * Empty list = no override; the UI should fall back to {@code assetId}.
     */
    List<AffectedAssetRef> affectedAssets,
    List<com.martecyber.ares.tags.TagDto> tags
) {
    public record ReferenceRef(Long id, Long catalogId, String catalogCode, String title, String description,
                               String url, String faviconUrl, boolean kevListed, int exploitCount) {}
    public record ScoreRef(Long typeId, String typeTitle, BigDecimal score, String metadata, boolean isDefault) {}
    public record AffectedAssetRef(Long id, String code, String identifier, String type) {}

    /** CVE priority signal (KEV/PoC) for a single CVE, looked up in batch by cveId — see
     *  DetectionService.loadCvePriorities. Absent from the map = no CVE KB entry yet, defaults
     *  to {@code NONE} rather than null so ReferenceRef building can always look it up safely. */
    public record CvePriority(boolean kevListed, int exploitCount) {
        public static final CvePriority NONE = new CvePriority(false, 0);
    }

    /** Copies this DTO with a different tag list — used by DetectionService to attach
     *  batch-loaded tags after the rest of the DTO has already been built. */
    public DetectionDto withTags(List<com.martecyber.ares.tags.TagDto> tags) {
        return new DetectionDto(id, projectId, assetId, assetCode, assetIdentifier, severity, status,
            title, description, rawData, sourceType, sourceTemplateId, occurrenceCount, createdAt, updatedAt,
            lastSeen, references, scores, affectedAssets, tags);
    }

    public static DetectionDto from(Detection d, java.util.Map<Long, String> scoreTypeNames,
                                    java.util.Map<Long, com.martecyber.ares.assets.Asset> assetMap,
                                    List<AffectedAssetRef> affectedAssets,
                                    java.util.Map<Long, String> catalogCodes) {
        return from(d, scoreTypeNames, assetMap, affectedAssets, catalogCodes, java.util.Map.of());
    }

    public static DetectionDto from(Detection d, java.util.Map<Long, String> scoreTypeNames,
                                    java.util.Map<Long, com.martecyber.ares.assets.Asset> assetMap,
                                    List<AffectedAssetRef> affectedAssets,
                                    java.util.Map<Long, String> catalogCodes,
                                    java.util.Map<String, CvePriority> cvePriorityByCveId) {
        List<ReferenceRef> refs = d.getReferences().stream()
            .map(r -> {
                String catalogCode = catalogCodes.getOrDefault(r.getCatalogId(), "");
                CvePriority priority = "CVE".equals(catalogCode)
                    ? cvePriorityByCveId.getOrDefault(r.getTitle(), CvePriority.NONE)
                    : CvePriority.NONE;
                return new ReferenceRef(r.getId(), r.getCatalogId(), catalogCode, r.getTitle(), r.getDescription(),
                    r.getUrl(), r.getFaviconUrl(), priority.kevListed(), priority.exploitCount());
            })
            .toList();
        List<ScoreRef> scores = d.getScores().stream()
            .map(s -> new ScoreRef(s.getTypeId(), scoreTypeNames.getOrDefault(s.getTypeId(), ""),
                s.getScore(), s.getMetadata(), s.isDefault()))
            .toList();
        com.martecyber.ares.assets.Asset asset = d.getAssetId() != null ? assetMap.get(d.getAssetId()) : null;
        return new DetectionDto(d.getId(), d.getProjectId(), d.getAssetId(),
            asset != null ? asset.getCode() : null,
            asset != null ? asset.getIdentifier() : null,
            d.getSeverity(), d.getStatus(), d.getTitle(), d.getDescription(),
            d.getRawData(), d.getSourceType(), d.getSourceTemplateId(), d.getOccurrenceCount(),
            d.getCreatedAt(), d.getUpdatedAt(), d.getLastSeen(), refs, scores,
            affectedAssets != null ? affectedAssets : List.of(), List.of());
    }

    public static DetectionDto from(Detection d, java.util.Map<Long, String> scoreTypeNames,
                                    java.util.Map<Long, com.martecyber.ares.assets.Asset> assetMap,
                                    List<AffectedAssetRef> affectedAssets) {
        return from(d, scoreTypeNames, assetMap, affectedAssets, java.util.Map.of());
    }

    public static DetectionDto from(Detection d, java.util.Map<Long, String> scoreTypeNames,
                                    java.util.Map<Long, com.martecyber.ares.assets.Asset> assetMap) {
        return from(d, scoreTypeNames, assetMap, List.of(), java.util.Map.of());
    }

    public static DetectionDto from(Detection d, java.util.Map<Long, String> scoreTypeNames) {
        return from(d, scoreTypeNames, java.util.Map.of(), List.of(), java.util.Map.of());
    }
}
