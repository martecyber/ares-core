package com.martecyber.ares.projects.rules;

import com.martecyber.ares.agents.tasks.AgentToolSpec;
import com.martecyber.ares.agents.tasks.AgentToolSpecRegistry;
import com.martecyber.ares.common.PlatformSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Pure Mockito unit test — confirms header/rate-limit/concurrency injection is driven entirely
 *  by a tool's own AgentToolSpec.Field#ruleBinding (via AgentToolSpecRegistry), never by a
 *  hardcoded per-tool switch in ares-core. Uses synthetic fixture specs, not any real plugin's
 *  actual field shape. */
class EngagementRuleEnforcerTest {

    private ProjectRuleRepository repo;
    private PlatformSettingsService settings;
    private AgentToolSpecRegistry agentToolSpecRegistry;
    private EngagementRuleEnforcer enforcer;

    @BeforeEach
    void setUp() {
        repo = mock(ProjectRuleRepository.class);
        settings = mock(PlatformSettingsService.class);
        when(settings.getTimezone()).thenReturn("UTC");
        agentToolSpecRegistry = mock(AgentToolSpecRegistry.class);
        enforcer = new EngagementRuleEnforcer(repo, settings, agentToolSpecRegistry);
    }

    private ProjectRule rule(String type, Map<String, Object> config) {
        ProjectRule r = new ProjectRule();
        r.setProjectId(1L);
        r.setRuleType(type);
        r.setEnabled(true);
        r.setConfig(toJson(config));
        return r;
    }

    private static String toJson(Map<String, Object> m) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(m); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private AgentToolSpec.Field field(String key, String ruleBinding, boolean repeat) {
        return new AgentToolSpec.Field(key, "string", null, null, null, null, null,
            false, null, null, repeat, null, null, null, ruleBinding);
    }

    private AgentToolSpec spec(AgentToolSpec.Field... fields) {
        return new AgentToolSpec("fixture-tool", "Fixture Tool", "fixture-tool", "default",
            Set.of(), Set.of(), null, "positional", null, null, null, false, null, List.of(fields));
    }

    @Test
    void injectsOnlyIntoTheFieldTheSpecTagsWithEachRuleBinding() {
        when(repo.findByProjectIdAndEnabled(1L, true)).thenReturn(List.of(
            rule("rate_limit", Map.of("value", 100, "unit", "rps")),
            rule("max_concurrency", Map.of("value", 5))
        ));
        when(agentToolSpecRegistry.find("fixture-tool")).thenReturn(java.util.Optional.of(spec(
            field("myRateField", "rate_limit", false),
            field("myConcurrencyField", "concurrency", false),
            field("unboundField", null, false)
        )));

        Map<String, Object> args = new HashMap<>();
        enforcer.applyToTaskArgs(1L, "fixture-tool", args, true);

        assertEquals(100, args.get("myRateField"));
        assertEquals(5, args.get("myConcurrencyField"));
        assertFalse(args.containsKey("unboundField"));
    }

    @Test
    void injectsHeadersAsAnArrayIntoTheHeadersBoundField() {
        when(repo.findByProjectIdAndEnabled(1L, true)).thenReturn(List.of(
            rule("required_header", new HashMap<>(Map.of("name", "X-Test", "value", "1")))
        ));
        when(agentToolSpecRegistry.find("fixture-tool")).thenReturn(java.util.Optional.of(spec(
            field("customHeaders", "headers", true)
        )));

        Map<String, Object> args = new HashMap<>();
        enforcer.applyToTaskArgs(1L, "fixture-tool", args, true);

        assertEquals(List.of("X-Test: 1"), args.get("customHeaders"));
    }

    @Test
    void neverInjectsAnythingWhenTheToolIsNotCurrentlyRegistered() {
        when(repo.findByProjectIdAndEnabled(1L, true)).thenReturn(List.of(
            rule("max_concurrency", Map.of("value", 5))
        ));
        when(agentToolSpecRegistry.find("unregistered-tool")).thenReturn(java.util.Optional.empty());

        Map<String, Object> args = new HashMap<>();
        enforcer.applyToTaskArgs(1L, "unregistered-tool", args, true);

        assertTrue(args.isEmpty());
    }

    @Test
    void neverOverridesAnAlreadyStricterValue() {
        when(repo.findByProjectIdAndEnabled(1L, true)).thenReturn(List.of(
            rule("max_concurrency", Map.of("value", 50))
        ));
        when(agentToolSpecRegistry.find("fixture-tool")).thenReturn(java.util.Optional.of(spec(
            field("myConcurrencyField", "concurrency", false)
        )));

        Map<String, Object> args = new HashMap<>();
        args.put("myConcurrencyField", 5); // operator already set a stricter value than the rule
        enforcer.applyToTaskArgs(1L, "fixture-tool", args, true);

        assertEquals(5, args.get("myConcurrencyField"));
    }
}
