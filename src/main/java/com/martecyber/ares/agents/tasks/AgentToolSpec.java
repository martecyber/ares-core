package com.martecyber.ares.agents.tasks;

import java.util.List;
import java.util.Set;

/**
 * Shape of the {@code agent-tool.json} resource a plugin ships at its resources root to declare
 * an agent-executed scan tool — pure data, read directly out of the plugin's JAR by {@code
 * PluginLoader} (via {@code PluginClassLoader#readOwnResourceBytes}, no {@code META-INF/services}
 * entry and no Spring bean involved, unlike {@code ImportParser}). Interpreted identically by
 * three very different consumers so none of them needs to know a specific tool exists:
 * <ul>
 *   <li>ares-core ({@code WorkflowGraphValidator}/{@code WorkflowRunService}/{@code
 *       AgentTaskService}, via {@link AgentToolSpecRegistry}) — validates a workflow node's args
 *       against {@link #fields()}, enforces {@link #maxTargets()} against the node's own
 *       {@code batchSize}, and resolves {@link #resultFormat()} for the completed task's import.</li>
 *   <li>ares-agent (Python) — a generic interpreter walks {@link #fields()} to build the tool's
 *       argv, using {@link #targetEmission()} for how {@code targets} gets emitted and, when
 *       {@link #customBuilder()} is set, delegating that one tool's command construction to an
 *       isolated subprocess running the plugin's own module instead (see that record's own doc).</li>
 *   <li>ares-ui — renders one form field per {@link #fields()} entry, generically, replacing what
 *       used to be a hand-built {@code XConfigPanel.vue} per tool.</li>
 * </ul>
 *
 * Replaces {@code AgentToolCatalog.ToolDescriptor} (a static, in-core Java map with no
 * regex/range validation and a {@code resultFormat} field that was declared but never actually
 * read by anything — this record's {@link #resultFormat()} is the one that matters now).
 */
public record AgentToolSpec(
    String toolId,
    String displayName,
    /** The binary the agent looks for on PATH ({@code shutil.which}) to decide whether this
     *  tool is available on a given host — usually equal to {@link #toolId()}, not assumed to be. */
    String binary,
    /** Must match the {@code formatId} of the {@code ImportParser} this same plugin registers
     *  (Phase 1's SPI) — ties a completed agent task straight to the parser that reads its
     *  output, the same way {@code AgentTaskService#complete} already calls {@code
     *  ImportService#runImport(tool, format, ...)} today, just no longer held together only by
     *  both sides happening to default to the literal string {@code "default"}. */
    String resultFormat,
    /** Scope-entry kinds operators may pick when targeting via {@code scope_entries}. */
    Set<String> validScopeKinds,
    /** Asset types operators may pick when targeting via {@code project_assets}. */
    Set<String> validAssetTypes,
    /** {@code null} = no limit (the common case — most tools accept a whole batch of targets in
     *  one invocation). {@code 1} means this tool cannot be given more than one target per task
     *  (e.g. wpscan refuses outright; ffuf has no multi-target CLI mode) — {@code
     *  WorkflowGraphValidator} turns that into a hard, save-time requirement that the node's own
     *  {@code batchSize} is exactly 1, so the agent never has to loop/merge multiple runs itself. */
    Integer maxTargets,
    /** How the resolved {@code targets} list becomes part of the command line — {@code
     *  "positional"} (nmap, masscan: each target its own bare argv entry), {@code "joined"}
     *  (naabu, subfinder: comma-joined into one flag's value), {@code "list-file"} (httpx, dnsx,
     *  nuclei, katana: written one-per-line to a temp file, passed via a flag), or {@code
     *  "single-flag"} (wpscan: its one allowed target — see {@link #maxTargets()} — is the value
     *  of {@link #targetFlag()} rather than positional). */
    String targetEmission,
    /** The flag whose value carries the resolved target(s) — required for every {@link
     *  #targetEmission()} except {@code "positional"} (which has no flag at all, each target is
     *  its own bare argv entry): {@code "-host"}/{@code "-d"}-style for {@code "joined"} (one
     *  flag, comma-joined value), {@code "-l"}/{@code "-list"}-style for {@code "list-file"} (one
     *  flag, value = the temp file's path), {@code "--url"}-style for {@code "single-flag"}. Null
     *  for {@code "positional"}. */
    String targetFlag,
    /** Static argv tokens the agent emits right after the binary path, before every other
     *  field/target emission — covers a tool's own output-format flag(s) (nmap's {@code -oX},
     *  naabu/httpx/dnsx's {@code -json -o}, nuclei/katana's {@code -jsonl -o}, wpscan's
     *  {@code -o ... --format json --no-banner}, ...) plus any other always-on, non-operator-
     *  configurable flag (quiet/no-color modes like {@code -silent}/{@code -no-color}). The
     *  single literal token {@code "{outPath}"} is replaced with the task's actual output file
     *  path (always {@code workdir/<toolId>.out}, chosen by the agent — parsers read the
     *  uploaded bytes, never the filename, so the extension is purely cosmetic). Null/empty for
     *  a tool whose {@link #customBuilder()} builds its own full command line (ffuf) — there is
     *  nothing generic left for the agent to prepend. */
    List<String> outputArgs,
    /** Best-effort minimum version constraint (e.g. {@code "7.90"}) — the agent compares its own
     *  detected version by extracting leading dot-separated numeric components and comparing them
     *  positionally; a version string on either side that doesn't parse this way is treated as
     *  compatible rather than blocking the tool, since CLI tool version strings aren't reliably
     *  semver. {@code null} means no constraint. */
    String minVersion,
    /** True if this tool needs the agent process to be running as root/elevated to work
     *  correctly (matches {@code CapabilityEntry#canRoot}, reported at every heartbeat). */
    boolean requiresRoot,
    /** Set only for a tool whose command construction genuinely can't be expressed by {@link
     *  #fields()} alone (ffuf's {@code FUZZ1}/{@code FUZZ2} multi-position wildcard splitting is
     *  the one case known today) — see {@link CustomBuilder}'s own doc for the execution model. */
    CustomBuilder customBuilder,
    List<Field> fields
) {
    public AgentToolSpec {
        if (validScopeKinds == null) validScopeKinds = Set.of();
        if (validAssetTypes == null) validAssetTypes = Set.of();
        if (outputArgs == null) outputArgs = List.of();
        if (fields == null) fields = List.of();
    }

    /** An optional plugin-supplied Python module ({@code builder.py}, read from the same plugin
     *  JAR's resources) that computes one tool's command line instead of the generic interpreter.
     *  Never imported and called inside the agent's own long-lived process — the agent spawns it
     *  as a short-lived, isolated subprocess for just that one invocation, handing it only the
     *  specific field values it declares needing (via stdin JSON) and reading back {@code
     *  {cmd: [...], outPath: "..."}} (via stdout JSON); the module is never given the agent's own
     *  auth token or config, and never spawns the actual scan tool itself — the agent's main
     *  process does that with whatever command the module returned. Synced (downloaded once,
     *  cached, re-verified against {@link #sha256()} whenever the plugin's own {@code
     *  toolSpecsVersion} changes) only when that agent's operator has explicitly opted in
     *  ({@code allow_agent_plugin_code}, off by default) — otherwise the tool simply isn't
     *  reported as available from that agent, the same as a missing binary. */
    public record CustomBuilder(
        /** Resource path inside the plugin JAR, e.g. {@code "builder.py"}. */
        String module,
        /** sha256 of the module's actual bytes — computed by {@code PluginLoader} straight from
         *  the JAR at load time (never trusted from the plugin author's own {@code
         *  agent-tools.json}, which only needs {@link #module()}; whatever it puts here, if
         *  anything, gets overwritten). Self-verifying and needs no manual sync when the module
         *  changes — reuses the exact same checksum mechanism {@code PluginService} already
         *  computes for the plugin JAR itself, just applied to this one embedded file. */
        String sha256
    ) {}

    /** Returns a copy with {@link CustomBuilder#sha256()} replaced — used by {@code PluginLoader}
     *  right after reading the module's actual bytes off the JAR, so every consumer of this
     *  record (the registry, the {@code /tool-specs} endpoint, the agent) only ever sees a
     *  verified checksum. No-ops (returns {@code this}) when {@link #customBuilder()} is null. */
    public AgentToolSpec withCustomBuilderSha256(String sha256) {
        if (customBuilder == null) return this;
        return new AgentToolSpec(toolId, displayName, binary, resultFormat, validScopeKinds, validAssetTypes,
            maxTargets, targetEmission, targetFlag, outputArgs, minVersion, requiresRoot,
            new CustomBuilder(customBuilder.module(), sha256), fields);
    }

    /** One declarative argument. Every field the generic interpreter (agent) and validator
     *  (core) both need to agree on — deliberately flat rather than a type hierarchy, since a
     *  plugin author writes this by hand as JSON. */
    public record Field(
        String key,
        /** {@code "string"} | {@code "number"} | {@code "enum"} | {@code "boolean_flag"} |
         *  {@code "server_fetch"}. {@code server_fetch} generalizes today's KB-wordlist-download
         *  special case in ffuf's Python builder — the agent fetches {@link #urlTemplate()} with
         *  {@code {value}} substituted for this field's own value, caches the result, and uses
         *  the local file path as the flag's actual value. */
        String type,
        /** CLI flag this field's value is emitted after (e.g. {@code "-p"}). {@code null} for an
         *  {@code enum} field whose own value IS the flag (nmap's {@code scanType}: {@code -sS}/
         *  {@code -sT}/...), and for a value meant to be appended positionally with no flag. */
        String flag,
        /** Only for {@code type: "enum"}. */
        List<String> options,
        /** Only for {@code type: "string"} — a regex the value must fully match. */
        String pattern,
        /** Only for {@code type: "number"} — inclusive bounds, either may be {@code null}. */
        Double min,
        Double max,
        /** Unconditionally mandatory (e.g. masscan's {@code ports} — unlike nmap's, which is
         *  optional). False (the common case) for anything only ever optional or conditionally
         *  required — see {@link #requiredIfField()}. */
        boolean required,
        /** This field is only required when {@code requiredIfField} has exactly the value {@code
         *  requiredIfEquals} (both {@code null} = never conditionally required — combine with
         *  {@link #required()} for "always required" instead). Mirrors a field that only matters
         *  under some other field's specific value. */
        String requiredIfField,
        String requiredIfEquals,
        /** True for an array-valued field emitted as one repeated {@code flag value} pair per
         *  entry (e.g. ffuf/httpx custom headers). */
        boolean repeat,
        /** Only for {@code type: "server_fetch"} — a URL path with a single {@code {value}}
         *  placeholder, resolved against this ares-core instance (the same one the agent already
         *  authenticates to for heartbeat/tasks). */
        String urlTemplate,
        /** When set, the emitted value is this string with {@code {value}} substituted for the
         *  field's actual value, instead of the raw value itself — e.g. httpx's {@code
         *  userAgent} field emits {@code -H "User-Agent: {value}"} rather than a dedicated flag.
         *  {@code null} (the common case) means use the raw value as-is. */
        String valueTemplate,
        /** Used in place of the field's value when the task's own args omit it — e.g. katana's
         *  {@code fieldScope} defaults to {@code "rdn"} (restrict crawling to the root domain)
         *  specifically so an operator who never touches this field still gets safe-by-default
         *  scoping, not "no scope restriction at all". {@code null} (the common case) means an
         *  absent field is simply not emitted. */
        String defaultValue,
        /** {@code "rate_limit"} | {@code "concurrency"} | {@code "headers"} | {@code null}. Ties
         *  this field to the equivalent {@code EngagementRuleEnforcer#applyToTaskArgs} project
         *  rule (ares-ui reads it via {@code useEngagementRuleLocks}) purely for UI transparency —
         *  when an active rule caps/injects that value, the form shows it as a locked, non-
         *  editable value (with an explicit "override" opt-out) instead of a plain input, so the
         *  operator isn't surprised when the server overrides or augments what they typed. Never
         *  affects validation or emission — the actual enforcement always happens server-side in
         *  {@code AgentTaskService#createAll}, regardless of what the form showed. Different tools
         *  use different field keys for the "same" real-world knob (naabu's {@code rate} vs.
         *  nuclei's {@code rateLimit}), which is exactly why this needs its own tag rather than
         *  matching on {@link #key()}. */
        String ruleBinding
    ) {}
}
