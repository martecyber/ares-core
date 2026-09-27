package com.martecyber.ares.projects.rules;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.agents.tasks.AgentToolSpec;
import com.martecyber.ares.agents.tasks.AgentToolSpecRegistry;
import com.martecyber.ares.common.PlatformSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads enabled rules for a project and either blocks task creation (time_window)
 * or mutates task args to inject headers and rate limits into supported tools.
 *
 * <p>Which field a given tool actually uses for "rate limit" / "concurrency" / "headers" is
 * read from that tool's own {@link AgentToolSpec.Field#ruleBinding()} (declared by the plugin,
 * see that field's own doc) via {@link AgentToolSpecRegistry} — never hardcoded here. A tool
 * with no field carrying a given ruleBinding simply doesn't receive that injection; ares-core
 * itself has no notion of which real tools exist or what their argument shapes look like.
 */
@Component
public class EngagementRuleEnforcer {

    private static final Logger log = LoggerFactory.getLogger(EngagementRuleEnforcer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProjectRuleRepository repo;
    private final PlatformSettingsService settings;
    private final AgentToolSpecRegistry agentToolSpecRegistry;

    public EngagementRuleEnforcer(ProjectRuleRepository repo, PlatformSettingsService settings,
                                   AgentToolSpecRegistry agentToolSpecRegistry) {
        this.repo = repo;
        this.settings = settings;
        this.agentToolSpecRegistry = agentToolSpecRegistry;
    }

    /**
     * Enforces time_window rules. Throws HTTP 422 if outside the allowed window and bypass is false.
     * Returns silently when bypass=true or when no time_window rule is active.
     */
    public void checkTimeWindow(Long projectId, boolean bypass) {
        if (bypass) return;
        List<ProjectRule> rules = repo.findByProjectIdAndEnabled(projectId, true);
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of(settings.getTimezone()));
        int currentHour = now.getHour();
        int currentDow = now.getDayOfWeek().getValue(); // 1=Mon … 7=Sun

        for (ProjectRule r : rules) {
            if (!"time_window".equals(r.getRuleType())) continue;
            Map<String, Object> cfg = parseConfig(r.getConfig());

            // Support both new "HH:MM" string format and legacy integer hour format
            int startMins = parseTimeMins(cfg, "startTime", "startHour");
            int endMins   = parseTimeMins(cfg, "endTime",   "endHour");
            int currentMins = currentHour * 60 + now.getMinute();

            if (startMins < 0 || endMins < 0) continue;

            @SuppressWarnings("unchecked")
            List<Object> days = (List<Object>) cfg.get("daysOfWeek");

            boolean dayAllowed = days == null || days.isEmpty() ||
                days.stream().anyMatch(d -> toInt(d) != null && toInt(d) == currentDow);
            if (!dayAllowed) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Project rule violation: today is outside the allowed testing days");
            }

            boolean timeAllowed;
            if (startMins <= endMins) {
                timeAllowed = currentMins >= startMins && currentMins < endMins;
            } else {
                // Overnight window e.g. 22:00–06:00
                timeAllowed = currentMins >= startMins || currentMins < endMins;
            }
            if (!timeAllowed) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Project rule violation: current time is outside the allowed testing window (" +
                    minsToStr(startMins) + "–" + minsToStr(endMins) + ")");
            }
        }
    }

    /**
     * Mutates taskArgs in place to inject headers, rate limits, and concurrency limits for the given tool.
     * Also calls checkTimeWindow — throws if outside window and bypass=false.
     */
    public void applyToTaskArgs(Long projectId, String tool, Map<String, Object> taskArgs, boolean bypass) {
        checkTimeWindow(projectId, bypass);

        List<ProjectRule> rules = repo.findByProjectIdAndEnabled(projectId, true);
        List<String> headers = new ArrayList<>();
        Integer rateLimitRps = null;
        Integer maxConcurrency = null;

        for (ProjectRule r : rules) {
            Map<String, Object> cfg = parseConfig(r.getConfig());
            switch (r.getRuleType()) {
                case "required_header" -> {
                    String name = (String) cfg.get("name");
                    String value = (String) cfg.get("value");
                    if (name != null && !name.isBlank() && value != null) {
                        headers.add(name + ": " + value);
                    }
                }
                case "required_user_agent" -> {
                    String ua = (String) cfg.get("userAgent");
                    if (ua != null && !ua.isBlank()) {
                        headers.add("User-Agent: " + ua);
                    }
                }
                case "rate_limit" -> {
                    Integer val = toInt(cfg.get("value"));
                    String unit = (String) cfg.get("unit");
                    if (val != null && val > 0) {
                        int rps = "rpm".equals(unit) ? Math.max(1, val / 60) : val;
                        if (rateLimitRps == null || rps < rateLimitRps) rateLimitRps = rps;
                    }
                }
                case "max_concurrency" -> {
                    Integer val = toInt(cfg.get("value"));
                    if (val != null && val > 0) {
                        if (maxConcurrency == null || val < maxConcurrency) maxConcurrency = val;
                    }
                }
                default -> { /* severity_override and time_window not injected */ }
            }
        }

        if (headers.isEmpty() && rateLimitRps == null && maxConcurrency == null) return;
        injectIntoTool(tool, taskArgs, headers, rateLimitRps, maxConcurrency);
    }

    // ── Generic, spec-driven injection ────────────────────────────────────────

    private void injectIntoTool(String tool, Map<String, Object> args,
                                List<String> headers, Integer rateLimitRps, Integer maxConcurrency) {
        AgentToolSpec spec = tool == null ? null : agentToolSpecRegistry.find(tool).orElse(null);
        if (spec == null) {
            log.debug("EngagementRuleEnforcer: tool '{}' is not currently registered — nothing to inject into", tool);
            return;
        }
        for (AgentToolSpec.Field field : spec.fields()) {
            if (field.ruleBinding() == null) continue;
            switch (field.ruleBinding()) {
                case "headers" -> injectHeaders(args, field.key(), headers);
                case "rate_limit" -> injectMax(args, field.key(), rateLimitRps);
                case "concurrency" -> injectMax(args, field.key(), maxConcurrency);
                default -> { /* unknown ruleBinding value — ignore rather than fail the task */ }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void injectHeaders(Map<String, Object> args, String key, List<String> headers) {
        if (headers.isEmpty()) return;
        Object raw = args.get(key);
        if (raw instanceof Map<?, ?>) {
            // Legacy shape some already-persisted args may still carry: {"Name": "value"}.
            Map<String, Object> map = new LinkedHashMap<>((Map<String, Object>) raw);
            for (String h : headers) {
                int sep = h.indexOf(": ");
                if (sep > 0) map.putIfAbsent(h.substring(0, sep), h.substring(sep + 2));
            }
            args.put(key, map);
        } else {
            // Current shape (AgentToolSpec.Field: type "string", repeat: true): ["Name: value", ...].
            List<String> existing = raw instanceof List<?> ? new ArrayList<>((List<String>) raw) : new ArrayList<>();
            for (String h : headers) {
                if (!existing.contains(h)) existing.add(h);
            }
            args.put(key, existing);
        }
    }

    /** Injects {@code value} for {@code key} only when stricter (smaller) than the current value. */
    private void injectMax(Map<String, Object> args, String key, Integer value) {
        if (value == null) return;
        Integer current = toInt(args.get(key));
        if (current == null || value < current) args.put(key, value);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> parseConfig(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return MAPPER.readValue(json, new TypeReference<>() {}); }
        catch (Exception e) { return Map.of(); }
    }

    private static Integer toInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return null; }
    }

    /** Returns total minutes since midnight for a "HH:MM" string, or falls back to the legacy integer hour. Returns -1 if unparseable. */
    private static int parseTimeMins(Map<String, Object> cfg, String timeKey, String hourKey) {
        Object timeVal = cfg.get(timeKey);
        if (timeVal instanceof String s && s.contains(":")) {
            String[] parts = s.split(":");
            try {
                int h = Integer.parseInt(parts[0].trim());
                int m = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
                return h * 60 + m;
            } catch (NumberFormatException e) { /* fall through */ }
        }
        Integer h = toInt(cfg.get(hourKey));
        return h != null ? h * 60 : -1;
    }

    private static String minsToStr(int mins) {
        return String.format("%02d:%02d", mins / 60, mins % 60);
    }
}
