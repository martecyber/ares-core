package com.martecyber.ares.dashboards;

import com.fasterxml.jackson.databind.JsonNode;
import com.martecyber.ares.assets.AssetService;
import com.martecyber.ares.detections.DetectionService;
import com.martecyber.ares.findings.FindingService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Executes the generic widget types (AQL_COUNT/AQL_CHART/AQL_LIST) — every bespoke widget type
 * fetches its own data client-side from the same endpoints the pre-remodel dashboard pages
 * already called (see the dashboards remodel plan), so there's nothing for this service to do for
 * those; {@link #dataFor} returns {@code null} and the caller skips the entry.
 */
@Service
public class DashboardWidgetDataService {

    private final FindingService findingService;
    private final AssetService assetService;
    private final DetectionService detectionService;

    public DashboardWidgetDataService(FindingService findingService, AssetService assetService,
                                       DetectionService detectionService) {
        this.findingService = findingService;
        this.assetService = assetService;
        this.detectionService = detectionService;
    }

    public Object dataFor(DashboardWidget widget, JsonNode config, DashboardLevel level, Long scopeId) {
        Long projectId = level == DashboardLevel.PROJECT ? scopeId : null;
        Long orgId = level == DashboardLevel.ORGANIZATION ? scopeId : null;

        return switch (widget.getType()) {
            case AQL_COUNT -> Map.of("count", count(config, projectId, orgId));
            case AQL_CHART -> Map.of("buckets", groupedCount(config, projectId, orgId));
            case AQL_LIST -> Map.of("items", list(config, projectId, orgId));
            // null: an unrecognized/stale widget type (see DashboardWidget.getType()) — nothing
            // to execute for it, same as any other bespoke type this dispatch doesn't handle.
            case null, default -> null;
        };
    }

    private long count(JsonNode config, Long projectId, Long orgId) {
        String entity = text(config, "entity");
        String aql = noFilterFallback(text(config, "aql"));
        return switch (entity) {
            case "finding" -> findingService.countByAql(projectId, orgId, false, aql);
            case "asset" -> assetService.countByAql(orgId, projectId, aql);
            case "detection" -> detectionService.countByAql(projectId, orgId, aql);
            default -> throw unsupportedEntity(entity);
        };
    }

    private List<com.martecyber.ares.aql.compile.AqlGroupCountSupport.GroupCount> groupedCount(
            JsonNode config, Long projectId, Long orgId) {
        String entity = text(config, "entity");
        String aql = noFilterFallback(text(config, "aql"));
        String groupByField = text(config, "groupByField");
        String dateBucket = blankToNull(text(config, "dateBucket"));
        String seriesField = blankToNull(text(config, "seriesField"));
        String sortMode = blankToNull(text(config, "sortMode"));
        Integer topN = intOrNull(config, "topN");
        return switch (entity) {
            case "finding" -> findingService.countGroupedByAql(projectId, orgId, false, aql, groupByField, dateBucket, seriesField, topN, sortMode);
            case "asset" -> assetService.countGroupedByAql(orgId, projectId, aql, groupByField, dateBucket, seriesField, topN, sortMode);
            case "detection" -> detectionService.countGroupedByAql(projectId, orgId, aql, groupByField, dateBucket, seriesField, topN, sortMode);
            default -> throw unsupportedEntity(entity);
        };
    }

    private List<?> list(JsonNode config, Long projectId, Long orgId) {
        String entity = text(config, "entity");
        String aql = noFilterFallback(text(config, "aql"));
        String sortField = blankToNull(text(config, "sortField"));
        String sortDir = text(config, "sortDir");
        sortDir = sortDir.isBlank() ? "desc" : sortDir;
        int limit = limit(config);
        return switch (entity) {
            case "finding" -> findingService.listByAql(projectId, orgId, false, aql, sortField, sortDir, 0, limit)
                .getContent();
            case "asset" -> assetService.listByAql(orgId, projectId, aql, sortField, sortDir, 0, limit).getContent();
            case "detection" -> detectionService.listByAql(projectId, orgId, aql, sortField, sortDir, 0, limit)
                .getContent();
            default -> throw unsupportedEntity(entity);
        };
    }

    private static int limit(JsonNode config) {
        JsonNode n = config == null ? null : config.get("limit");
        int requested = n == null || n.isNull() ? 8 : n.asInt(8);
        return Math.max(1, Math.min(requested, 20));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static Integer intOrNull(JsonNode config, String field) {
        JsonNode n = config == null ? null : config.get(field);
        return n == null || n.isNull() ? null : n.asInt();
    }

    private static ResponseStatusException unsupportedEntity(String entity) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Dashboard widgets don't support entity '" + entity + "' — pick finding, asset, or detection");
    }

    /** {@code AqlParser.parse("")} throws (the grammar has no "match everything" empty
     *  production) — the QueryBar/list-view convention for "no filter" is to skip the AQL path
     *  entirely, but a dashboard widget always goes through it, so an admin leaving the AQL field
     *  blank (meaning "count every row in scope") needs an always-true stand-in instead. Every
     *  entity here has a numeric, always-positive {@code id} field, so this is universally valid
     *  and universally true. */
    private static String noFilterFallback(String aql) {
        return aql == null || aql.isBlank() ? "id != 0" : aql;
    }

    private static String text(JsonNode config, String field) {
        JsonNode n = config == null ? null : config.get(field);
        return n == null || n.isNull() ? "" : n.asText("");
    }
}
