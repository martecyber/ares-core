package com.martecyber.ares.workflows.integrations;

import com.martecyber.ares.plugins.Plugin;
import com.martecyber.ares.plugins.PluginRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntegrationActionRegistryTest {

    private static IntegrationActionHandler handler(String type, String... actionCodes) {
        return handler(type, Set.of("project"), actionCodes);
    }

    private static IntegrationActionHandler handler(String type, Set<String> scopes, String... actionCodes) {
        return new IntegrationActionHandler() {
            @Override public String integrationType() { return type; }
            @Override public String integrationTypeLabel() { return type + " label"; }
            @Override public Set<String> supportedScopes() { return scopes; }
            @Override public List<IntegrationActionDescriptor> describeActions() {
                return List.of(actionCodes).stream().map(c -> new IntegrationActionDescriptor(c, c + " label")).toList();
            }
            @Override public List<IntegrationInstanceDescriptor> listInstances(String scopeKind, Long scopeId) { return List.of(); }
            @Override public Long start(String actionCode, Long integrationInstanceId, String scopeKind, Long scopeId, Map<String, Object> params) { return null; }
            @Override public IntegrationActionResult checkStatus(Long refId) { return null; }
        };
    }

    /** Empty-repo stub — enough for tests that don't care about the missing-plugin message's
     *  exact plugin-naming logic (see IntegrationActionRegistryMissingHandlerMessageTest). */
    private static PluginRepository emptyPluginRepo() {
        PluginRepository repo = mock(PluginRepository.class);
        when(repo.findByPluginId(org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        when(repo.findAll()).thenReturn(List.of());
        return repo;
    }

    @Test
    void allReturnsEveryRegisteredHandler() {
        var registry = new IntegrationActionRegistry(List.of(handler("a", "X"), handler("b", "Y")), emptyPluginRepo());
        assertEquals(2, registry.all().size());
    }

    @Test
    void requireResolvesByType() {
        var h = handler("caido-api", "PULL_SCOPE");
        var registry = new IntegrationActionRegistry(List.of(h), emptyPluginRepo());
        assertSame(h, registry.require("caido-api"));
    }

    @Test
    void requireThrowsForUnknownType() {
        var registry = new IntegrationActionRegistry(List.of(), emptyPluginRepo());
        var ex = assertThrows(IllegalArgumentException.class, () -> registry.require("nope"));
        assertTrue(ex.getMessage().contains("nope"));
    }

    @Test
    void missingHandlerMessageNamesTheInstalledButDisabledPlugin() {
        PluginRepository repo = mock(PluginRepository.class);
        Plugin p = mock(Plugin.class);
        when(p.getPluginId()).thenReturn("tenable");
        when(p.getDisplayName()).thenReturn("Tenable");
        when(p.isEnabled()).thenReturn(false);
        when(repo.findByPluginId("tenable-mssp")).thenReturn(Optional.empty());
        when(repo.findAll()).thenReturn(List.of(p));
        var registry = new IntegrationActionRegistry(List.of(), repo);

        String msg = registry.missingHandlerMessage("tenable-mssp");

        assertTrue(msg.contains("Tenable"));
        assertTrue(msg.contains("disabled"));
    }

    @Test
    void missingHandlerMessageFallsBackWhenNoPluginMatches() {
        var registry = new IntegrationActionRegistry(List.of(), emptyPluginRepo());
        String msg = registry.missingHandlerMessage("nope");
        assertTrue(msg.contains("nope"));
        assertTrue(msg.contains("no matching plugin is"));
    }

    @Test
    void hasActionTrueOnlyForRegisteredTypeAndCode() {
        var registry = new IntegrationActionRegistry(List.of(handler("caido-api", "PULL_SCOPE", "PUSH_FINDINGS")), emptyPluginRepo());
        assertTrue(registry.hasAction("caido-api", "PULL_SCOPE"));
        assertTrue(registry.hasAction("caido-api", "PUSH_FINDINGS"));
        assertFalse(registry.hasAction("caido-api", "DO_MAGIC"));
        assertFalse(registry.hasAction("unknown-type", "PULL_SCOPE"));
    }

    @Test
    void constructorRejectsDuplicateType() {
        var ex = assertThrows(IllegalStateException.class,
            () -> new IntegrationActionRegistry(List.of(handler("caido-api", "A"), handler("caido-api", "B")), emptyPluginRepo()));
        assertTrue(ex.getMessage().contains("caido-api"));
    }

    @Test
    void supportsScopeReflectsEachHandlersOwnDeclaredScopes() {
        var registry = new IntegrationActionRegistry(List.of(
            handler("caido-api", Set.of("project"), "PULL_SCOPE"),
            handler("kb-sync", Set.of("platform"), "CVE_UPDATE")), emptyPluginRepo());
        assertTrue(registry.supportsScope("caido-api", "project"));
        assertFalse(registry.supportsScope("caido-api", "platform"));
        assertTrue(registry.supportsScope("kb-sync", "platform"));
        assertFalse(registry.supportsScope("kb-sync", "project"));
        assertFalse(registry.supportsScope("unknown-type", "project"));
    }
}
