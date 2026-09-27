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
 * Detection status prep for Research Boards: adds a new "under_investigation" status (entered
 * when a detection is added to a Research Board — see the research feature build), and retires
 * two statuses that are being folded into existing ones rather than kept as distinct concepts:
 * "archived" -> "fixed" (the only status archived was ever reached from — V143's own transition
 * table has archived reachable solely via fixed->archived) and "out_of_scope" -> "ignored" (both
 * already meant "closed, no analyst action needed").
 *
 * A Java migration for the same reason V189__DetectionStatusRename did — the AQL/workflow/
 * dashboard rewrite below needs a real JSON tree walk, not jsonb-path/regex text surgery, to
 * safely reach status literals nested inside heterogeneous CONDITION/COUNT_COMPARE/ASSIGN_VARIABLE
 * node shapes without risking corrupting an unrelated part of a live workflow or dashboard. This
 * duplicates V189's small rewrite helpers rather than sharing a base class with it: V189 already
 * ran and is checksum-locked by Flyway, so it must never be touched again, and a shared helper
 * class both migrations depend on would effectively let a future change retroactively alter what
 * an already-applied migration does.
 *
 * Deliberately NOT touched (see the Research Boards plan for the full reasoning):
 *  - detection_status_history.from_status/to_status text — a true record of what happened at the
 *    time; only the live detection.status/status_id are remapped.
 *  - DetectionIterationStat's "open/escalated/closed" monitoring-area bucket and its
 *    report-template-baked merge-field key — a different, intentionally-unrenamed vocabulary
 *    (same reasoning V189 used); "under_investigation" isn't counted by it yet.
 */
public class V190__DetectionStatusResearchPrep extends BaseJavaMigration {

    /** archived/out_of_scope -> the status their data (and any stored AQL referencing them)
     *  moves to. Unlike V189's RENAME map this isn't a pure rename: the old status rows get
     *  deleted afterward, not kept under a new name. */
    private static final Map<String, String> FOLD_INTO = new LinkedHashMap<>();
    static {
        FOLD_INTO.put("archived", "fixed");
        FOLD_INTO.put("out_of_scope", "ignored");
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void migrate(Context context) throws Exception {
        Connection conn = context.getConnection();
        insertUnderInvestigation(conn);
        foldStatusColumns(conn);
        rewriteDashboardWidgets(conn);
        rewriteWorkflows(conn);
        deleteFoldedStatuses(conn);
    }

    private void insertUnderInvestigation(Connection conn) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO ares.detection_status (name, description, means_closed, escalatable) VALUES (?, ?, FALSE, FALSE)")) {
            ps.setString(1, "under_investigation");
            ps.setString(2, "Under investigation on a Research Board");
            ps.executeUpdate();
        }
        String[][] transitions = {
            {"new", "under_investigation"},
            {"reopened", "under_investigation"},
            {"under_investigation", "reopened"},
            {"under_investigation", "affected"},
            {"under_investigation", "not_affected"},
        };
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO ares.detection_status_transition (from_status_id, to_status_id) "
                    + "SELECT s1.id, s2.id FROM ares.detection_status s1, ares.detection_status s2 "
                    + "WHERE s1.name = ? AND s2.name = ?")) {
            for (String[] t : transitions) {
                ps.setString(1, t[0]);
                ps.setString(2, t[1]);
                ps.executeUpdate();
            }
        }
    }

    /** The plain-varchar "wire format" copies of the status name — see renameStatusColumns's
     *  twin in V189 for why both detection_status_id (FK, untouched here since we DELETE the old
     *  rows below rather than rename them) and detection.status (denormalized varchar) exist. */
    private void foldStatusColumns(Connection conn) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE ares.detection d SET status = ?, status_id = "
                    + "(SELECT id FROM ares.detection_status WHERE name = ?) WHERE d.status = ?")) {
            for (Map.Entry<String, String> e : FOLD_INTO.entrySet()) {
                ps.setString(1, e.getValue());
                ps.setString(2, e.getValue());
                ps.setString(3, e.getKey());
                ps.executeUpdate();
            }
        }
    }

    private void deleteFoldedStatuses(Connection conn) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM ares.detection_status WHERE name = ?")) {
            for (String oldName : FOLD_INTO.keySet()) {
                ps.setString(1, oldName);
                ps.executeUpdate();
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
                if (status != null && FOLD_INTO.containsKey(status)) {
                    config.put("status", FOLD_INTO.get(status));
                    changed = true;
                }
            }
            default -> { }
        }
        return changed;
    }

    private boolean rewriteAqlHolder(ObjectNode holder, String entityField) {
        if (!"detection".equals(holder.path(entityField).asText(null))) return false;
        String aql = holder.path("aql").asText(null);
        if (aql == null) return false;
        String rewritten = rewriteAqlText(aql);
        if (rewritten.equals(aql)) return false;
        holder.put("aql", rewritten);
        return true;
    }

    private String rewriteAqlText(String aql) {
        String result = aql;
        for (Map.Entry<String, String> e : FOLD_INTO.entrySet()) {
            result = result.replaceAll("\\b" + Pattern.quote(e.getKey()) + "\\b", e.getValue());
        }
        return result;
    }
}
