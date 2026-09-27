package db.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Detection status rename: escalated -> affected, false_positive -> not_affected,
 * dismissed -> ignored, solved -> fixed ("new", "reopened", "out_of_scope", "archived"
 * untouched — the request was specifically about these four).
 *
 * A Java migration — every other file in this directory is plain SQL — specifically because two
 * of the four places this rename has to reach (Workflow.graphDefinition's CONDITION/COUNT_COMPARE/
 * ASSIGN_VARIABLE node {@code aql} text, and DashboardWidget.config's {@code aql} text) are
 * free-form AQL strings nested at different depths inside heterogeneous jsonb shapes (a
 * COUNT_COMPARE node nests its aql two levels down, inside its left/right operands;
 * ASSIGN_VARIABLE nests it inside a sources[] array). Hand-rolled jsonb-path/regex text surgery
 * across those shapes risks silently corrupting a live workflow or dashboard; Jackson's real JSON
 * tree walk below doesn't. The other two places (detection_status/detection/
 * detection_status_history's plain varchar columns, ACTION_UPDATE_DETECTION_STATUS's single
 * literal config.status) are plain JDBC statements right below — no SQL file could do those more
 * simply, they just live here too so the whole rename is one migration, one transaction.
 *
 * Deliberately NOT touched:
 *  - DetectionIterationStat.area ("open"/"escalated"/"closed", a monitoring-iteration bucket name
 *    that happens to reuse the word "escalated" — a different vocabulary from detection status,
 *    see DetectionIterationStatsService) and its report-template merge-field key of the same name
 *    ({{detectionsByIteration.*.escalated}}). That key is baked into already-generated/uploaded
 *    .docx report templates in S3, which this migration has no way to safely rewrite — renaming it
 *    here would silently break every existing template's merge field with no recovery path, for a
 *    rename that wasn't actually requested (the ask was the detection *status* vocabulary, not the
 *    iteration-area one).
 *  - WorkflowRun.graphSnapshot (the frozen graph copy taken at run time) — a historical record of
 *    what the graph looked like when that run executed, not live config; rewriting it would
 *    misrepresent history rather than fix anything.
 *  - Finding's own, separate "false_positive" status (finding_status table) — same literal word,
 *    unrelated vocabulary.
 */
public class V189__DetectionStatusRename extends BaseJavaMigration {

    private static final Map<String, String> RENAME = new LinkedHashMap<>();
    static {
        RENAME.put("escalated", "affected");
        RENAME.put("false_positive", "not_affected");
        RENAME.put("dismissed", "ignored");
        RENAME.put("solved", "fixed");
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void migrate(Context context) throws Exception {
        Connection conn = context.getConnection();
        renameStatusColumns(conn);
        rewriteDashboardWidgets(conn);
        rewriteWorkflows(conn);
    }

    /** The plain-varchar "wire format" copies of the status name — detection_status.name is the
     *  source of truth (status_id FKs to it, so detection_status_transition needs no changes at
     *  all), detection.status and detection_status_history.from_status/to_status are raw strings
     *  kept in sync with it by the app on every write (see V143's migration comment). */
    private void renameStatusColumns(Connection conn) throws Exception {
        String[] statements = {
            "UPDATE ares.detection_status SET name = ? WHERE name = ?",
            "UPDATE ares.detection SET status = ? WHERE status = ?",
            "UPDATE ares.detection_status_history SET from_status = ? WHERE from_status = ?",
            "UPDATE ares.detection_status_history SET to_status = ? WHERE to_status = ?",
        };
        for (String sql : statements) {
            for (Map.Entry<String, String> e : RENAME.entrySet()) {
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, e.getValue());
                    ps.setString(2, e.getKey());
                    ps.executeUpdate();
                }
            }
        }
    }

    private void rewriteDashboardWidgets(Connection conn) throws Exception {
        record Row(long id, String config) { }
        List<Row> rows = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, config::text FROM ares.dashboard_widget")) {
            while (rs.next()) rows.add(new Row(rs.getLong(1), rs.getString(2)));
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE ares.dashboard_widget SET config = ?::jsonb WHERE id = ?")) {
            for (Row row : rows) {
                if (!(MAPPER.readTree(row.config()) instanceof ObjectNode config)) continue;
                if (!rewriteAqlHolder(config, "entity")) continue;
                ps.setString(1, MAPPER.writeValueAsString(config));
                ps.setLong(2, row.id());
                ps.executeUpdate();
            }
        }
    }

    private void rewriteWorkflows(Connection conn) throws Exception {
        record Row(long id, String graph) { }
        List<Row> rows = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, graph_definition::text FROM ares.workflow")) {
            while (rs.next()) rows.add(new Row(rs.getLong(1), rs.getString(2)));
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE ares.workflow SET graph_definition = ?::jsonb WHERE id = ?")) {
            for (Row row : rows) {
                if (!(MAPPER.readTree(row.graph()) instanceof ObjectNode root)) continue;
                boolean changed = false;
                JsonNode nodes = root.get("nodes");
                if (nodes != null && nodes.isArray()) {
                    for (JsonNode nodeEl : nodes) {
                        if (!(nodeEl instanceof ObjectNode node)) continue;
                        String type = node.path("type").asText(null);
                        if (!(node.get("data") instanceof ObjectNode data)) continue;
                        if (!(data.get("config") instanceof ObjectNode config)) continue;
                        changed |= rewriteWorkflowNode(type, config);
                    }
                }
                if (!changed) continue;
                ps.setString(1, MAPPER.writeValueAsString(root));
                ps.setLong(2, row.id());
                ps.executeUpdate();
            }
        }
    }

    /** One case per node type that can carry a detection-entity {@code aql} string or (for
     *  ACTION_UPDATE_DETECTION_STATUS) a literal status name — see WorkflowGraphValidator's own
     *  validate* methods for the authoritative shape each of these mirrors. */
    private boolean rewriteWorkflowNode(String type, ObjectNode config) {
        if (type == null) return false;
        boolean changed = false;
        switch (type) {
            case "CONDITION" -> {
                String mode = config.path("mode").asText(null);
                if (mode == null || "ENTITY_MATCH".equals(mode)) {
                    changed |= rewriteAqlHolder(config, "entityType");
                } else if ("COUNT_COMPARE".equals(mode)) {
                    if (config.get("left") instanceof ObjectNode left) changed |= rewriteAqlHolder(left, "entityType");
                    if (config.get("right") instanceof ObjectNode right) changed |= rewriteAqlHolder(right, "entityType");
                }
            }
            case "ASSIGN_VARIABLE" -> {
                JsonNode sources = config.get("sources");
                if (sources != null && sources.isArray()) {
                    for (JsonNode s : sources) {
                        if (s instanceof ObjectNode source) changed |= rewriteAqlHolder(source, "entityType");
                    }
                }
            }
            case "ACTION_UPDATE_DETECTION_STATUS" -> {
                String status = config.path("status").asText(null);
                if (status != null && RENAME.containsKey(status)) {
                    config.put("status", RENAME.get(status));
                    changed = true;
                }
            }
            default -> { }
        }
        return changed;
    }

    /** Rewrites {@code holder.aql} in place, only when {@code holder.<entityField>} is
     *  "detection" — every other entity's aql (asset/finding/cve/...) is left completely alone. */
    private boolean rewriteAqlHolder(ObjectNode holder, String entityField) {
        if (!"detection".equals(holder.path(entityField).asText(null))) return false;
        String aql = holder.path("aql").asText(null);
        if (aql == null) return false;
        String rewritten = rewriteAqlText(aql);
        if (rewritten.equals(aql)) return false;
        holder.put("aql", rewritten);
        return true;
    }

    /** Renames every bounded occurrence of an old status token — bare ({@code status ==
     *  escalated}), quoted ({@code status == "escalated"}), or as a {@code status.<name>} virtual
     *  field ({@code status.escalated < 2026-01-01}) — using \b so an unrelated word that merely
     *  contains one of these as a substring (e.g. "resolved", a real AQL/Finding-status field
     *  elsewhere) never matches. */
    private String rewriteAqlText(String aql) {
        String result = aql;
        for (Map.Entry<String, String> e : RENAME.entrySet()) {
            result = result.replaceAll("\\b" + Pattern.quote(e.getKey()) + "\\b", e.getValue());
        }
        return result;
    }
}
