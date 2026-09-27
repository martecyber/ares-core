package com.martecyber.ares.agents.tasks;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Live registry of every currently-loaded plugin's {@link AgentToolSpec} — same
 * register/unregister-at-runtime shape as {@code ImportService}'s own {@code ImportParser} list
 * (Phase 1), called from {@code PluginLoader} on install/uninstall/enable/disable, no restart
 * needed. Unlike {@code ImportParser} there's no Spring bean lifecycle involved on the way in —
 * {@code PluginLoader} deserializes the plugin's {@code agent-tool.json} directly and hands the
 * plain record here.
 */
@Service
public class AgentToolSpecRegistry {

    private final Map<String, AgentToolSpec> byToolId = new ConcurrentHashMap<>();
    /** Only populated for a spec whose {@link AgentToolSpec#customBuilder()} is set — the raw
     *  bytes of its {@code builder.py}, read once from the plugin JAR at load time (see {@code
     *  PluginLoader}) and served from here on every {@code GET
     *  /agent/tool-specs/{toolId}/builder} request rather than re-opening the JAR per request. */
    private final Map<String, byte[]> builderModules = new ConcurrentHashMap<>();
    /** Bumped on every register/unregister — the value ares-agent compares its cached copy
     *  against (via the heartbeat response's {@code toolSpecsVersion}) to know whether it's
     *  worth re-fetching {@code GET /agent/tool-specs} at all, instead of doing that on every
     *  30s heartbeat regardless of whether anything actually changed. */
    private final AtomicLong version = new AtomicLong();

    /** {@code builderModuleBytes} is null for the common case (no {@code customBuilder}). */
    public void register(AgentToolSpec spec, byte[] builderModuleBytes) {
        byToolId.put(spec.toolId(), spec);
        if (builderModuleBytes != null) builderModules.put(spec.toolId(), builderModuleBytes);
        version.incrementAndGet();
    }

    /** No-ops if a DIFFERENT spec is currently registered under this toolId — mirrors the same
     *  "don't remove something newer than what I loaded" defensiveness {@code PluginLoader} uses
     *  elsewhere (e.g. its bean-name-collision handling), relevant if a replace installs the new
     *  version before the old one's unload runs. */
    public void unregister(AgentToolSpec spec) {
        if (byToolId.remove(spec.toolId(), spec)) {
            builderModules.remove(spec.toolId());
            version.incrementAndGet();
        }
    }

    public Optional<AgentToolSpec> find(String toolId) {
        return Optional.ofNullable(byToolId.get(toolId));
    }

    public Optional<byte[]> findBuilderModule(String toolId) {
        return Optional.ofNullable(builderModules.get(toolId));
    }

    public List<AgentToolSpec> list() {
        return List.copyOf(byToolId.values());
    }

    public long version() {
        return version.get();
    }

    /** Throws BAD_REQUEST for an unknown tool — replaces {@code AgentToolCatalog#describe}. */
    public AgentToolSpec describe(String toolId) {
        return find(toolId).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Tool '" + toolId + "' is not supported. Allowed: " + byToolId.keySet()));
    }

    /** Key-only check — every key must be a declared field (or the universal {@code targets}/
     *  {@code targetsFrom}), with no required-ness enforced. Used where the args are still a
     *  draft with no real target/project context yet (a saved {@code AgentTaskTemplate} is
     *  project-agnostic; requiring {@code targets} there would reject every template). {@link
     *  #validateArgs} is the stricter check for anywhere a task is actually about to run. */
    public void validateArgKeys(String toolId, Set<String> keys) {
        AgentToolSpec spec = describe(toolId);
        Set<String> declared = declaredKeys(spec);
        for (String key : keys) {
            if ("targets".equals(key) || "targetsFrom".equals(key)) continue;
            if (!declared.contains(key)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Tool '" + toolId + "' does not accept arg '" + key + "'");
            }
        }
    }

    /** Full validation: {@code targets} present, every {@link AgentToolSpec.Field#required()}
     *  (or conditionally-required) field present, every declared field's value matching its
     *  type/pattern/range/enum, and no undeclared keys. Replaces {@code
     *  AgentToolCatalog#validateArgs} — which only ever checked key presence, never value shape;
     *  the regex/range checks here are new (Fase 2's whole point: that validation used to live
     *  only in ares-agent's own Python, client-side, after the workflow/task was already saved). */
    public void validateArgs(String toolId, Map<String, Object> args) {
        AgentToolSpec spec = describe(toolId);
        if (args == null) args = Map.of();
        if (args.get("targets") == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Tool '" + toolId + "' requires arg 'targets'");
        }
        Set<String> declared = declaredKeys(spec);
        for (AgentToolSpec.Field f : spec.fields()) {
            Object value = args.get(f.key());
            boolean required = f.required() || (f.requiredIfField() != null && f.requiredIfEquals() != null
                && f.requiredIfEquals().equals(String.valueOf(args.get(f.requiredIfField()))));
            if (required && value == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Tool '" + toolId + "' requires arg '" + f.key() + "'");
            }
            if (value != null) validateFieldValue(toolId, f, value);
        }
        for (String key : args.keySet()) {
            if ("targets".equals(key) || "targetsFrom".equals(key)) continue;
            if (!declared.contains(key)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Tool '" + toolId + "' does not accept arg '" + key + "'");
            }
        }
    }

    private static Set<String> declaredKeys(AgentToolSpec spec) {
        Set<String> keys = new HashSet<>();
        for (AgentToolSpec.Field f : spec.fields()) keys.add(f.key());
        return keys;
    }

    private void validateFieldValue(String toolId, AgentToolSpec.Field f, Object value) {
        if (f.repeat()) {
            if (value instanceof List<?> list) {
                for (Object item : list) validateSingleValue(toolId, f, item);
            }
            return;
        }
        validateSingleValue(toolId, f, value);
    }

    private void validateSingleValue(String toolId, AgentToolSpec.Field f, Object value) {
        String s = String.valueOf(value);
        switch (f.type()) {
            case "enum" -> {
                if (f.options() != null && !f.options().isEmpty() && !f.options().contains(s)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Tool '" + toolId + "': '" + f.key() + "' must be one of " + f.options());
                }
            }
            case "number" -> {
                double d;
                try {
                    d = Double.parseDouble(s);
                } catch (NumberFormatException e) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Tool '" + toolId + "': '" + f.key() + "' must be a number");
                }
                if (f.min() != null && d < f.min()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Tool '" + toolId + "': '" + f.key() + "' must be >= " + f.min());
                }
                if (f.max() != null && d > f.max()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Tool '" + toolId + "': '" + f.key() + "' must be <= " + f.max());
                }
            }
            case "string" -> {
                if (f.pattern() != null && !s.matches(f.pattern())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Tool '" + toolId + "': '" + f.key() + "' has an invalid value");
                }
            }
            default -> { /* boolean_flag, server_fetch: no generic value-format check */ }
        }
    }
}
