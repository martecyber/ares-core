package com.martecyber.ares.kb.attack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

@Component
public class AttackStixParser {

    private static final Logger log = LoggerFactory.getLogger(AttackStixParser.class);

    public static final String ENTERPRISE_URL = "https://raw.githubusercontent.com/mitre/cti/master/enterprise-attack/enterprise-attack.json";
    public static final String MOBILE_URL     = "https://raw.githubusercontent.com/mitre/cti/master/mobile-attack/mobile-attack.json";
    public static final String ICS_URL        = "https://raw.githubusercontent.com/mitre/cti/master/ics-attack/ics-attack.json";

    public record ParseResult(
        List<AttackTactic> tactics,
        List<AttackTechnique> techniques,
        List<AttackMitigation> mitigations,
        List<MitigatesRelationship> mitigatesRelationships,
        String matrix
    ) {}

    /** One STIX {@code relationship} object with {@code relationship_type: "mitigates"} — {@code
     *  source_ref} is the mitigating {@code course-of-action}'s own stix_id, {@code target_ref}
     *  the mitigated {@code attack-pattern} (technique)'s stix_id. Phase 5 of the AQL-wide
     *  initiative: these were previously discarded entirely (no {@code case "relationship"}
     *  existed in {@link #parse}) — AttackService resolves each pair to real
     *  {@code attack_technique_mitigation} FK rows once techniques/mitigations are saved. */
    public record MitigatesRelationship(String mitigationStixId, String techniqueStixId) {}

    private final ObjectMapper objectMapper;

    public AttackStixParser() {
        this.objectMapper = new ObjectMapper();
    }

    public ParseResult parse(InputStream in, String matrix, Consumer<Integer> progress) throws IOException {
        List<AttackTactic> tactics = new ArrayList<>();
        List<AttackTechnique> techniques = new ArrayList<>();
        List<AttackMitigation> mitigations = new ArrayList<>();
        List<MitigatesRelationship> mitigatesRelationships = new ArrayList<>();

        JsonNode root = objectMapper.readTree(in);
        JsonNode objects = root.get("objects");
        if (objects == null || !objects.isArray())
            throw new IOException("Invalid STIX bundle: missing objects array");

        int total = objects.size();
        Map<String, Integer> tacticOrderMap = buildTacticOrderMap(objects);

        for (int i = 0; i < total; i++) {
            JsonNode obj = objects.get(i);
            String type = text(obj, "type");
            try {
                switch (type != null ? type : "") {
                    case "x-mitre-tactic"  -> tactics.add(parseTactic(obj, matrix, tacticOrderMap));
                    case "attack-pattern"  -> techniques.add(parseTechnique(obj, matrix));
                    case "course-of-action" -> mitigations.add(parseMitigation(obj, matrix));
                    case "relationship" -> {
                        MitigatesRelationship rel = parseMitigatesRelationship(obj);
                        if (rel != null) mitigatesRelationships.add(rel);
                    }
                }
            } catch (Exception e) {
                log.warn("Error parsing STIX {} {}: {}", type, text(obj, "id"), e.getMessage());
            }
            if (progress != null && i % 200 == 0) progress.accept(i * 100 / total);
        }

        log.info("ATT&CK {} parsed: {} tactics, {} techniques, {} mitigations, {} mitigates-relationships",
            matrix, tactics.size(), techniques.size(), mitigations.size(), mitigatesRelationships.size());
        return new ParseResult(tactics, techniques, mitigations, mitigatesRelationships, matrix);
    }

    /** Returns null for any relationship that isn't a course-of-action-mitigates-attack-pattern
     *  edge (STIX bundles have many other relationship_type/source/target combinations — uses,
     *  subtechnique-of, revoked-by, etc. — none of which this initiative's scope covers). */
    private MitigatesRelationship parseMitigatesRelationship(JsonNode obj) {
        if (!"mitigates".equals(text(obj, "relationship_type"))) return null;
        String sourceRef = text(obj, "source_ref");
        String targetRef = text(obj, "target_ref");
        if (sourceRef == null || targetRef == null) return null;
        if (!sourceRef.startsWith("course-of-action--") || !targetRef.startsWith("attack-pattern--")) return null;
        return new MitigatesRelationship(sourceRef, targetRef);
    }

    private Map<String, Integer> buildTacticOrderMap(JsonNode objects) {
        Map<String, String> stixIdToShortName = new HashMap<>();
        for (JsonNode obj : objects) {
            if ("x-mitre-tactic".equals(text(obj, "type"))) {
                String stixId = text(obj, "id");
                String shortName = text(obj, "x_mitre_shortname");
                if (stixId != null && shortName != null) stixIdToShortName.put(stixId, shortName);
            }
        }

        Map<String, Integer> order = new HashMap<>();
        for (JsonNode obj : objects) {
            if ("x-mitre-matrix".equals(text(obj, "type"))) {
                JsonNode refs = obj.get("tactic_refs");
                if (refs != null && refs.isArray()) {
                    for (int i = 0; i < refs.size(); i++) {
                        String shortName = stixIdToShortName.get(refs.get(i).asText());
                        if (shortName != null) order.put(shortName, i);
                    }
                }
                break;
            }
        }
        return order;
    }

    private AttackTactic parseTactic(JsonNode obj, String matrix, Map<String, Integer> orderMap) {
        AttackTactic t = new AttackTactic();
        t.setStixId(text(obj, "id"));
        t.setName(text(obj, "name"));
        t.setDescription(text(obj, "description"));
        t.setShortName(text(obj, "x_mitre_shortname"));
        t.setMatrix(matrix);
        t.setAttackId(extractAttackId(obj));
        t.setSyncedAt(Instant.now());
        if (t.getShortName() != null) t.setOrder(orderMap.getOrDefault(t.getShortName(), 999));
        return t;
    }

    private AttackTechnique parseTechnique(JsonNode obj, String matrix) {
        AttackTechnique t = new AttackTechnique();
        t.setStixId(text(obj, "id"));
        t.setName(text(obj, "name"));
        t.setDescription(text(obj, "description"));
        t.setMatrix(matrix);
        t.setAttackId(extractAttackId(obj));
        t.setSubtechnique(t.getAttackId() != null && t.getAttackId().contains("."));
        t.setDeprecated(bool(obj, "x_mitre_deprecated"));
        t.setRevoked(bool(obj, "revoked"));
        t.setTactics(extractTactics(obj, matrix));
        t.setPlatforms(stringList(obj, "x_mitre_platforms"));
        t.setDataSources(stringList(obj, "x_mitre_data_sources"));
        t.setDetection(text(obj, "x_mitre_detection"));
        t.setPermissionsRequired(stringList(obj, "x_mitre_permissions_required"));
        t.setReferences(extractReferences(obj));
        t.setSyncedAt(Instant.now());
        return t;
    }

    /** Every {@code external_references} entry with a URL — the technique's own attack.mitre.org
     *  page plus every inline "(Citation: Name)" the description references. Previously discarded
     *  entirely (only {@code source_name: "mitre-attack"}'s {@code external_id} was read, to
     *  populate {@link AttackTechnique#getAttackId()}). */
    private List<AttackTechnique.Reference> extractReferences(JsonNode obj) {
        List<AttackTechnique.Reference> result = new ArrayList<>();
        JsonNode refs = obj.get("external_references");
        if (refs == null || !refs.isArray()) return result;
        for (JsonNode ref : refs) {
            String url = text(ref, "url");
            if (url == null) continue;
            String name = text(ref, "description");
            if (name == null) name = text(ref, "source_name");
            result.add(new AttackTechnique.Reference(url, name));
        }
        return result;
    }

    private AttackMitigation parseMitigation(JsonNode obj, String matrix) {
        AttackMitigation m = new AttackMitigation();
        m.setStixId(text(obj, "id"));
        m.setName(text(obj, "name"));
        m.setDescription(text(obj, "description"));
        m.setMatrix(matrix);
        m.setAttackId(extractAttackId(obj));
        m.setDeprecated(bool(obj, "x_mitre_deprecated"));
        m.setSyncedAt(Instant.now());
        return m;
    }

    private String extractAttackId(JsonNode obj) {
        JsonNode refs = obj.get("external_references");
        if (refs != null && refs.isArray()) {
            for (JsonNode ref : refs) {
                String src = text(ref, "source_name");
                if ("mitre-attack".equals(src)) {
                    String extId = text(ref, "external_id");
                    if (extId != null && (extId.startsWith("TA") || extId.startsWith("T") || extId.startsWith("M")))
                        return extId;
                }
            }
        }
        return null;
    }

    private List<String> extractTactics(JsonNode obj, String matrix) {
        List<String> result = new ArrayList<>();
        JsonNode phases = obj.get("kill_chain_phases");
        if (phases == null || !phases.isArray()) return result;
        String expected = switch (matrix) {
            case "mobile-attack" -> "mitre-mobile-attack";
            case "ics-attack"    -> "mitre-ics-attack";
            default              -> "mitre-attack";
        };
        for (JsonNode phase : phases) {
            if (expected.equals(text(phase, "kill_chain_name"))) {
                String name = text(phase, "phase_name");
                if (name != null) result.add(name);
            }
        }
        return result;
    }

    private List<String> stringList(JsonNode obj, String field) {
        List<String> result = new ArrayList<>();
        JsonNode arr = obj.get(field);
        if (arr != null && arr.isArray())
            for (JsonNode item : arr) if (item.isTextual()) result.add(item.asText());
        return result;
    }

    private String text(JsonNode obj, String field) {
        JsonNode n = obj.get(field);
        return (n != null && n.isTextual()) ? n.asText() : null;
    }

    private boolean bool(JsonNode obj, String field) {
        JsonNode n = obj.get(field);
        return n != null && n.isBoolean() && n.asBoolean();
    }
}
