package com.martecyber.ares.agents.tasks;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AgentToolSpecRegistryTest {

    private final AgentToolSpecRegistry registry = new AgentToolSpecRegistry();

    private AgentToolSpec.Field field(String key, String type, String flag) {
        return new AgentToolSpec.Field(key, type, flag, null, null, null, null,
            false, null, null, false, null, null, null, null);
    }

    private AgentToolSpec spec(List<AgentToolSpec.Field> fields) {
        return new AgentToolSpec("test-tool", "Test Tool", "test-tool", "default",
            Set.of("ip"), Set.of("host"), null, "positional", null, null, null, false, null, fields);
    }

    @Test
    void describeThrowsForAnUnknownTool() {
        var ex = assertThrows(ResponseStatusException.class, () -> registry.describe("bogus"));
        assertTrue(ex.getReason().contains("bogus"));
    }

    @Test
    void validateArgsRequiresTargets() {
        registry.register(spec(List.of()), null);
        var ex = assertThrows(ResponseStatusException.class,
            () -> registry.validateArgs("test-tool", Map.of()));
        assertTrue(ex.getReason().contains("targets"));
    }

    @Test
    void validateArgsRejectsAnUndeclaredKey() {
        registry.register(spec(List.of()), null);
        var ex = assertThrows(ResponseStatusException.class,
            () -> registry.validateArgs("test-tool", Map.of("targets", List.of("1.2.3.4"), "bogusArg", "x")));
        assertTrue(ex.getReason().contains("bogusArg"));
    }

    @Test
    void validateArgsEnforcesAnUnconditionallyRequiredField() {
        var f = field("ports", "string", "-p");
        var required = new AgentToolSpec.Field(f.key(), f.type(), f.flag(), f.options(), f.pattern(),
            f.min(), f.max(), true, null, null, false, null, null, null, null);
        registry.register(spec(List.of(required)), null);

        var ex = assertThrows(ResponseStatusException.class,
            () -> registry.validateArgs("test-tool", Map.of("targets", List.of("1.2.3.4"))));
        assertTrue(ex.getReason().contains("ports"));

        assertDoesNotThrow(() -> registry.validateArgs("test-tool",
            Map.of("targets", List.of("1.2.3.4"), "ports", "80,443")));
    }

    @Test
    void validateArgsEnforcesAConditionallyRequiredField() {
        var scanType = field("scanType", "enum", null);
        var ports = new AgentToolSpec.Field("ports", "string", "-p", null, null, null, null,
            false, "scanType", "-sS", false, null, null, null, null);
        registry.register(spec(List.of(scanType, ports)), null);

        // scanType != "-sS" → ports not required
        assertDoesNotThrow(() -> registry.validateArgs("test-tool",
            Map.of("targets", List.of("1.2.3.4"), "scanType", "-sn")));
        // scanType == "-sS" → ports required
        var ex = assertThrows(ResponseStatusException.class, () -> registry.validateArgs("test-tool",
            Map.of("targets", List.of("1.2.3.4"), "scanType", "-sS")));
        assertTrue(ex.getReason().contains("ports"));
    }

    @Test
    void validateArgsRejectsAnEnumValueOutsideItsOptions() {
        registry.register(spec(List.of(
            new AgentToolSpec.Field("scanType", "enum", null, List.of("-sS", "-sT"), null,
                null, null, false, null, null, false, null, null, null, null)
        )), null);
        var ex = assertThrows(ResponseStatusException.class, () -> registry.validateArgs("test-tool",
            Map.of("targets", List.of("1.2.3.4"), "scanType", "-sX")));
        assertTrue(ex.getReason().contains("scanType"));
    }

    @Test
    void validateArgsEnforcesNumericBounds() {
        registry.register(spec(List.of(
            new AgentToolSpec.Field("minRate", "number", "--min-rate", null, null,
                1.0, 1000.0, false, null, null, false, null, null, null, null)
        )), null);
        assertDoesNotThrow(() -> registry.validateArgs("test-tool",
            Map.of("targets", List.of("1.2.3.4"), "minRate", 500)));
        var ex = assertThrows(ResponseStatusException.class, () -> registry.validateArgs("test-tool",
            Map.of("targets", List.of("1.2.3.4"), "minRate", 5000)));
        assertTrue(ex.getReason().contains("minRate"));
    }

    @Test
    void validateArgsEnforcesAStringPattern() {
        registry.register(spec(List.of(
            new AgentToolSpec.Field("ports", "string", "-p", null, "^[0-9,\\-]+$",
                null, null, false, null, null, false, null, null, null, null)
        )), null);
        assertDoesNotThrow(() -> registry.validateArgs("test-tool",
            Map.of("targets", List.of("1.2.3.4"), "ports", "80,443")));
        var ex = assertThrows(ResponseStatusException.class, () -> registry.validateArgs("test-tool",
            Map.of("targets", List.of("1.2.3.4"), "ports", "80; rm -rf /")));
        assertTrue(ex.getReason().contains("ports"));
    }

    @Test
    void validateArgsChecksEachElementOfARepeatedField() {
        registry.register(spec(List.of(
            new AgentToolSpec.Field("headers", "string", "-H", null, "^[A-Za-z0-9:\\- ]+$",
                null, null, false, null, null, true, null, null, null, null)
        )), null);
        assertDoesNotThrow(() -> registry.validateArgs("test-tool",
            Map.of("targets", List.of("1.2.3.4"), "headers", List.of("X-Foo: bar", "X-Baz: qux"))));
        var ex = assertThrows(ResponseStatusException.class, () -> registry.validateArgs("test-tool",
            Map.of("targets", List.of("1.2.3.4"), "headers", List.of("X-Foo: bar", "$(evil)"))));
        assertTrue(ex.getReason().contains("headers"));
    }

    @Test
    void validateArgKeysIsPermissiveAboutRequiredFieldsButNotUnknownOnes() {
        var f = field("ports", "string", "-p");
        var required = new AgentToolSpec.Field(f.key(), f.type(), f.flag(), f.options(), f.pattern(),
            f.min(), f.max(), true, null, null, false, null, null, null, null);
        registry.register(spec(List.of(required)), null);

        // No 'targets', no 'ports' (required in validateArgs) — still fine here, this is the
        // template-draft check, not the dispatch-time one.
        assertDoesNotThrow(() -> registry.validateArgKeys("test-tool", Set.of("targetsFrom")));
        var ex = assertThrows(ResponseStatusException.class,
            () -> registry.validateArgKeys("test-tool", Set.of("bogusArg")));
        assertTrue(ex.getReason().contains("bogusArg"));
    }
}
