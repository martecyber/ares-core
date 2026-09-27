package com.martecyber.ares.agents.tasks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.aql.compile.PostgresSpecificationCompiler;
import com.martecyber.ares.aql.parser.AqlNode;
import com.martecyber.ares.aql.parser.AqlParser;
import com.martecyber.ares.assets.Asset;
import com.martecyber.ares.assets.AssetAqlRegistry;
import com.martecyber.ares.assets.AssetRepository;
import com.martecyber.ares.assets.AssetType;
import com.martecyber.ares.projects.ProjectAssetAccess;
import com.martecyber.ares.projects.ProjectScopeEntry;
import com.martecyber.ares.projects.ProjectScopeEntryRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Resolves a task's `targetsFrom` selector into the concrete `targets` list at the moment
 * the task becomes pending. For schedules this happens on every fire so changes to the
 * project's scope/assets are picked up automatically.
 *
 * Supported selectors (the `type` discriminator):
 *
 *  • <b>scope_wildcards</b> — every in-scope {@code domain_wildcard} entry. The
 *    {@code *.} prefix is stripped so the value goes cleanly into tools that don't
 *    accept wildcards (subfinder, dnsx, …). Set <code>preserveWildcard: true</code> in
 *    the selector to keep the prefix.
 *
 *  • <b>scope_entries</b> — scope entries filtered by <code>kinds: ["domain","cidr",…]</code>.
 *    Defaults to all in-scope entries when no kinds are given.
 *
 *  • <b>project_assets</b> — assets the project has visibility on, filtered by
 *    <code>assetType</code> (required). For SERVICE assets the identifier is split into
 *    <code>ip:port</code> form which is what nuclei / httpx expect.
 *
 *  • <b>asset_aql</b> — assets matching an AQL query (<code>aql</code>, required), AND-restricted
 *    to <code>assetTypes: [...]</code> when given (the caller — the Workflow node config panel —
 *    derives this from the selected tool's {@code ToolDescriptor.validAssetTypes}, the same way
 *    {@code project_assets}'s own {@code assetTypes} is client-derived; empty/absent = no
 *    restriction). Values not in {@link AssetType#ALL} are silently dropped rather than trusted
 *    verbatim into the composed AQL string — this field ultimately comes from a workflow node's
 *    stored JSON config, not a fixed server-side list, so it's treated as untrusted input.
 */
@Service
public class TargetResolver {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_TARGETS = 5_000;

    private final ProjectScopeEntryRepository scopeRepo;
    private final AssetRepository assetRepo;
    private final AssetAqlRegistry aqlRegistry;

    public TargetResolver(ProjectScopeEntryRepository scopeRepo, AssetRepository assetRepo,
                           AssetAqlRegistry aqlRegistry) {
        this.scopeRepo = scopeRepo;
        this.assetRepo = assetRepo;
        this.aqlRegistry = aqlRegistry;
    }

    /**
     * Reads {@code args}, resolves the selector if present, and returns a new args map
     * where {@code targetsFrom} has been replaced by a concrete {@code targets} list.
     * If only literal {@code targets} are present, returns args unchanged.
     *
     * The selector is intentionally stripped from the returned map so it never reaches
     * the tool argument validator (which rejects unknown keys). The schedule entity
     * keeps the selector in its own args column and re-resolves on each fire.
     */
    public Map<String, Object> resolveInto(Long projectId, Map<String, Object> args) {
        if (args == null) return Map.of();
        Object selector = args.get("targetsFrom");
        if (selector == null) return args; // literal targets only — pass through

        List<String> resolved = resolve(projectId, selector);
        Map<String, Object> out = new LinkedHashMap<>(args);
        out.remove("targetsFrom");
        out.put("targets", resolved);
        return out;
    }

    /**
     * Resolves without enforcing the MAX_TARGETS cap. Used by the scheduler so that
     * coverage rotation and batch-splitting downstream can still subdivide large result sets.
     */
    public Map<String, Object> resolveInto(Long projectId, Map<String, Object> args, boolean enforceLimit) {
        if (args == null) return Map.of();
        Object selector = args.get("targetsFrom");
        if (selector == null) return args;
        List<String> resolved = resolve(projectId, selector, enforceLimit);
        Map<String, Object> out = new LinkedHashMap<>(args);
        out.remove("targetsFrom");
        out.put("targets", resolved);
        return out;
    }

    /** Direct resolution with limit enforcement. Used by task creation and resolveInto. */
    public List<String> resolve(Long projectId, Object selectorRaw) {
        return resolve(projectId, selectorRaw, true);
    }

    /** Direct resolution without limit enforcement. Used by the preview endpoint. */
    public List<String> resolveUnlimited(Long projectId, Object selectorRaw) {
        return resolve(projectId, selectorRaw, false);
    }

    @SuppressWarnings("unchecked")
    private List<String> resolve(Long projectId, Object selectorRaw, boolean enforceLimit) {
        if (!(selectorRaw instanceof Map<?, ?> m)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "targetsFrom must be an object with a 'type' field");
        }
        Map<String, Object> selector = (Map<String, Object>) m;
        String type = String.valueOf(selector.getOrDefault("type", ""));
        List<String> raw = switch (type) {
            case "scope_wildcards" -> resolveScopeWildcards(projectId,
                Boolean.TRUE.equals(selector.get("preserveWildcard")));
            case "scope_entries"   -> resolveScopeEntries(projectId,
                stringList(selector.get("kinds")));
            case "project_assets"  -> resolveProjectAssets(projectId, assetTypesOf(selector),
                Boolean.TRUE.equals(selector.get("scopeStrict")));
            case "asset_aql"       -> resolveAssetAql(projectId,
                String.valueOf(selector.getOrDefault("aql", "")), assetTypesOf(selector));
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Unknown targetsFrom.type: " + type);
        };

        // Operator-curated excludes — let "Project assets" / "Scope entries" persist a
        // list of values that should be skipped even if they resolve. Recurring schedules
        // continue to pick up *new* matching items; only the explicitly-listed ones drop.
        List<String> excludes = stringList(selector.get("excludeValues"));
        List<String> out;
        if (excludes == null || excludes.isEmpty()) {
            out = raw;
        } else {
            Set<String> drop = new HashSet<>(excludes.size());
            for (String e : excludes) if (e != null) drop.add(e.trim().toLowerCase());
            out = new ArrayList<>(raw.size());
            for (String v : raw) {
                if (v != null && !drop.contains(v.trim().toLowerCase())) out.add(v);
            }
        }

        if (enforceLimit && out.size() > MAX_TARGETS) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                "Selector resolves to " + out.size() + " targets; limit is " + MAX_TARGETS);
        }
        return out;
    }

    /**
     * Accepts both the new {@code assetTypes: [...]} and the legacy {@code assetType: "ip"}
     * shapes so schedules persisted before this change still resolve.
     */
    @SuppressWarnings("unchecked")
    private static List<String> assetTypesOf(Map<String, Object> selector) {
        Object multi = selector.get("assetTypes");
        if (multi instanceof List<?> l) {
            List<String> out = new ArrayList<>(l.size());
            for (Object o : l) if (o != null) out.add(o.toString());
            return out;
        }
        Object single = selector.get("assetType");
        if (single != null && !single.toString().isBlank()) return List.of(single.toString());
        return List.of();
    }

    /** Same JSON shape, just returned as a Java map. Convenience for callers carrying raw JSON. */
    public Map<String, Object> readArgs(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return MAPPER.readValue(json, new TypeReference<>() {}); }
        catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid args JSON: " + e.getMessage());
        }
    }

    // ── selector implementations ───────────────────────────────────────────

    private List<String> resolveScopeWildcards(Long projectId, boolean preserveWildcard) {
        return scopeRepo.findByProjectIdOrderByCreatedAtAsc(projectId).stream()
            .filter(ProjectScopeEntry::isInScope)
            .filter(e -> "domain_wildcard".equalsIgnoreCase(e.getKind()))
            .map(ProjectScopeEntry::getValue)
            .map(v -> preserveWildcard ? v : (v.startsWith("*.") ? v.substring(2) : v))
            .distinct()
            .toList();
    }

    private List<String> resolveScopeEntries(Long projectId, List<String> kinds) {
        Set<String> wanted = (kinds == null || kinds.isEmpty()) ? null
            : kinds.stream().map(String::toLowerCase).collect(java.util.stream.Collectors.toSet());
        return scopeRepo.findByProjectIdOrderByCreatedAtAsc(projectId).stream()
            .filter(ProjectScopeEntry::isInScope)
            .filter(e -> wanted == null || wanted.contains(e.getKind().toLowerCase()))
            .map(this::valueForTool)
            .filter(v -> v != null && !v.isBlank())
            .distinct()
            .toList();
    }

    /**
     * Normalize a scope entry's value to a form scanners actually accept. The only
     * non-trivial case today is {@code domain_wildcard}: stored as {@code *.foo.com}
     * but every tool we ship rejects the leading {@code *.}, so we strip it.
     */
    private String valueForTool(ProjectScopeEntry e) {
        String v = e.getValue();
        if (v == null) return null;
        if ("domain_wildcard".equalsIgnoreCase(e.getKind()) && v.startsWith("*.")) {
            return v.substring(2);
        }
        return v;
    }

    private List<String> resolveProjectAssets(Long projectId, List<String> assetTypes, boolean scopeStrict) {
        if (assetTypes == null || assetTypes.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "project_assets selector requires at least one asset type");
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String type : assetTypes) {
            if (type == null || type.isBlank()) continue;
            var page = scopeStrict
                ? assetRepo.filterByProjectStrictInScope(projectId, type.toLowerCase(), PageRequest.of(0, MAX_TARGETS + 1))
                : assetRepo.filterByProjectInScope(projectId, type.toLowerCase(), PageRequest.of(0, MAX_TARGETS + 1));
            for (Asset a : page.getContent()) {
                String t = formatAssetForTarget(a);
                if (t != null && !t.isBlank() && seen.add(t)) out.add(t);
            }
        }
        return out;
    }

    private List<String> resolveAssetAql(Long projectId, String aql, List<String> assetTypes) {
        if (aql == null || aql.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "asset_aql selector requires 'aql'");
        }
        List<String> validTypes = assetTypes == null ? List.of()
            : assetTypes.stream().filter(AssetType.ALL::contains).distinct().toList();
        String composed = validTypes.isEmpty() ? aql
            : "(" + aql + ") AND type == [" + String.join(", ", validTypes) + "]";

        AqlNode node;
        try {
            node = AqlParser.parse(composed);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid asset_aql query: " + e.getMessage());
        }
        Specification<Asset> spec = new PostgresSpecificationCompiler<>(aqlRegistry)
            .compile(node)
            .and(assetProjectScopeSpecification(projectId));

        var page = assetRepo.findAll(spec, PageRequest.of(0, MAX_TARGETS + 1));
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (Asset a : page.getContent()) {
            String t = formatAssetForTarget(a);
            if (t != null && !t.isBlank() && seen.add(t)) out.add(t);
        }
        return out;
    }

    /** Mirrors AssetService's own project-scope predicate (Asset has no JPA association to
     *  Project, only the native {@code project_asset_access} join table) — duplicated rather than
     *  shared since AssetService's public listByAql() carries its own authenticated-request
     *  authorization check ({@code currentAuth()}/{@code orgScope}), which doesn't apply here:
     *  TargetResolver is called from contexts with no live HTTP request at all (a CRON-triggered
     *  Workflow run), trusting that its caller already established access to the project. */
    private Specification<Asset> assetProjectScopeSpecification(Long projectId) {
        return (root, query, cb) -> {
            var sub = query.subquery(Long.class);
            var paaRoot = sub.from(ProjectAssetAccess.class);
            sub.select(paaRoot.get("assetId"));
            sub.where(cb.and(
                cb.equal(paaRoot.get("projectId"), projectId),
                cb.equal(paaRoot.get("assetId"), root.get("id"))));
            return cb.exists(sub);
        };
    }

    /** Some asset types need reshaping before being handed to a tool. */
    private String formatAssetForTarget(Asset a) {
        String id = a.getIdentifier();
        if (id == null) return null;
        // SERVICE identifier is "ip:port/proto" — drop the protocol suffix so httpx / nuclei accept it.
        if ("service".equalsIgnoreCase(a.getType())) {
            int slash = id.lastIndexOf('/');
            return slash > 0 ? id.substring(0, slash) : id;
        }
        return id;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object v) {
        if (v == null) return List.of();
        if (v instanceof List<?> l) {
            List<String> out = new ArrayList<>(l.size());
            for (Object o : l) if (o != null) out.add(o.toString());
            return out;
        }
        return List.of(v.toString());
    }
}
