package com.martecyber.ares.aql;

import com.martecyber.ares.aql.compile.AqlPlanner;
import com.martecyber.ares.aql.compile.AqlVariableExpander;
import com.martecyber.ares.aql.dto.AqlFieldDto;
import com.martecyber.ares.aql.dto.AqlVariableDto;
import com.martecyber.ares.aql.parser.AqlParser;
import com.martecyber.ares.aql.registry.AqlField;
import com.martecyber.ares.aql.registry.AqlFieldType;
import com.martecyber.ares.aql.registry.RelationAqlField;
import com.martecyber.ares.aql.registry.RelationLeafAqlField;
import com.martecyber.ares.projects.Project;
import com.martecyber.ares.projects.ProjectRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Schema introspection and dry-run validation for AQL — lets the UI/CLI build field autocomplete
 * and give live feedback on a query as the user types, without hardcoding a field list that can
 * drift from the registry, and without executing anything.
 */
@RestController
@RequestMapping("/api/v1/aql")
public class AqlController {

    private final AqlRegistryLookup registries;
    private final AqlVariableExpander variableExpander;
    private final ProjectRepository projectRepo;

    public AqlController(AqlRegistryLookup registries, AqlVariableExpander variableExpander, ProjectRepository projectRepo) {
        this.registries = registries;
        this.variableExpander = variableExpander;
        this.projectRepo = projectRepo;
    }

    /** {@code attackTactic}/{@code cveKevDetail} are excluded — relation-only registries with no
     *  standalone REST {@code ?aql=} surface by product decision (only reachable nested as
     *  {@code attack.tactics.*}/{@code cve.kev.*}), so they'd be a dead end in any picker built
     *  from this list. */
    private static final java.util.Set<String> RELATION_ONLY_ENTITIES = java.util.Set.of("attackTactic", "cveKevDetail");

    @GetMapping("/entities")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public List<String> entities() {
        return registries.entityNames().stream().filter(e -> !RELATION_ONLY_ENTITIES.contains(e)).sorted().toList();
    }

    @GetMapping("/fields")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public List<AqlFieldDto> fields(@RequestParam String entity) {
        return registries.require(entity).allFields().stream()
            .map(f -> new AqlFieldDto(f.name(), typeLabel(f), f.kind().name(),
                f.supportedOperators().stream().map(Enum::name).toList(), f.allowedValues()))
            .toList();
    }

    /** {@code list[X]} instead of the raw enum name — a bare {@link RelationAqlField} (not one of
     *  its flattened {@code "<relation>.<leaf>"} entries, which report their own real leaf type
     *  normally) is never directly comparable, only a nesting point, so its own {@link
     *  AqlField#type()} is a meaningless placeholder ({@code STRING}, see that record's javadoc).
     *  {@code list[string]} for a plain {@code ARRAY_COLUMN} (HAS-only, e.g. platforms) makes the
     *  string-list-vs-relation-list distinction visible in the same {@code list[...]} vocabulary,
     *  rather than the enum name {@code STRING_LIST} looking unrelated to {@code list[cwe]}. */
    private String typeLabel(AqlField<?> f) {
        if (f instanceof RelationAqlField<?, ?> relation) return "list[" + relation.targetEntityName() + "]";
        // A flattened "<relation>.<leaf>" entry whose own leaf is ITSELF a bare RelationAqlField —
        // e.g. exploit's "cves.cwes" (cves -> CveEntry, whose own "cwes" is list[cwe]) — reports
        // AqlFieldType.STRING via RelationLeafAqlField#type()'s delegation to that placeholder type,
        // same trap this method exists to avoid for a top-level relation. Recurse into the leaf
        // rather than checking `f` directly so relation-of-relation chains of any depth (list[X]
        // nested inside list[Y] nested inside list[Z], ...) resolve to their real list[...] label.
        if (f instanceof RelationLeafAqlField<?, ?> relationLeaf) return typeLabel(relationLeaf.leaf());
        if (f.type() == AqlFieldType.STRING_LIST) return "list[string]";
        return f.type().name();
    }

    /** Parses and resolves {@code q} against {@code entity}'s registry without executing it —
     *  throws (mapped to 400 by GlobalExceptionHandler) on a syntax error or an unknown field,
     *  same as the real list endpoints would. {@code projectId}/{@code organizationId} are
     *  optional and only needed to validate a query using {@code {{...}}} context variables (AQL
     *  context-variables initiative) — without them, a variable needing project/org scope to
     *  resolve (an iteration variable, an SLA-period variable) fails validation with a clear
     *  message rather than silently skipping the check. */
    @GetMapping("/validate")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public Map<String, Object> validate(@RequestParam String entity, @RequestParam("q") String query,
                                         @RequestParam(required = false) Long projectId,
                                         @RequestParam(required = false) Long organizationId) {
        var ast = AqlParser.parse(variableExpander.expand(query, projectId, organizationId));
        AqlPlanner.validate(ast, registries.require(entity));
        return Map.of("valid", true);
    }

    /** {@code {{...}}} context variables available for autocomplete (AQL context-variables
     *  initiative) — {@code now} always; {@code current_iteration_start}/{@code
     *  current_iteration_end} only when {@code projectId} names a project with an iteration
     *  cadence set; {@code p0_sla_period}..{@code p4_sla_period} only when either scope param
     *  resolves to an organization. Response shape mirrors the frontend's {@code AqlVariableRef}
     *  exactly (see {@link AqlVariableDto}) so it can be handed straight to {@code
     *  computeAqlSuggestions} with no reshaping. */
    @GetMapping("/variables")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR','CLIENT_USER','CLIENT_ADMIN')")
    public List<AqlVariableDto> variables(@RequestParam(required = false) Long projectId,
                                           @RequestParam(required = false) Long organizationId) {
        List<AqlVariableDto> out = new ArrayList<>();
        out.add(new AqlVariableDto("now", "current date/time", "date"));

        String cadence = projectId == null ? null
            : projectRepo.findById(projectId).map(Project::getIterationCadence).orElse(null);
        if (cadence != null) {
            out.add(new AqlVariableDto("current_iteration_start", "start of the current iteration", "date"));
            out.add(new AqlVariableDto("current_iteration_end", "end of the current iteration", "date"));
        }

        if (projectId != null || organizationId != null) {
            for (int p = 0; p <= 4; p++) {
                out.add(new AqlVariableDto("p" + p + "_sla_period", "P" + p + " SLA period, in days", "duration"));
            }
        }
        return out;
    }
}
