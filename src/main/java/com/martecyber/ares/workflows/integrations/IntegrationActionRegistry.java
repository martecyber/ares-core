package com.martecyber.ares.workflows.integrations;

import com.martecyber.ares.plugins.PluginMessages;
import com.martecyber.ares.plugins.PluginRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single source of truth for "which {@link IntegrationActionHandler} owns which integration
 * type" — collects every Spring bean implementing the interface via constructor injection of
 * {@code List<IntegrationActionHandler>} (Spring's own auto-discovery, not a manually-maintained
 * switch), so a new handler becomes available to the validator, the run service, the poller, and
 * the listing endpoint simply by being a {@code @Component} on the classpath.
 *
 * <p>Backed by a {@link ConcurrentHashMap}, not the immutable map a plain constructor-built
 * registry would normally use, because {@code com.martecyber.ares.plugins.PluginLoader} calls
 * {@link #registerLate} to add a plugin-provided handler *after* the application context has
 * already finished starting (a plugin JAR installed at runtime was never on the classpath the
 * constructor's own {@code List<IntegrationActionHandler>} injection saw) — every read here must
 * be safe to run concurrently with that late write.
 */
@Component
public class IntegrationActionRegistry {

    private final Map<String, IntegrationActionHandler> byType = new ConcurrentHashMap<>();
    private final PluginRepository pluginRepo;

    public IntegrationActionRegistry(List<IntegrationActionHandler> handlers, PluginRepository pluginRepo) {
        this.pluginRepo = pluginRepo;
        for (IntegrationActionHandler handler : handlers) {
            registerLate(handler);
        }
    }

    /** Adds a handler discovered after startup (a plugin, installed or enabled without a
     *  restart — see {@code com.martecyber.ares.plugins.PluginLoader}). Same duplicate-type
     *  rejection as the constructor's own registration, so a plugin can never silently shadow an
     *  existing handler (built-in or another plugin's). */
    public void registerLate(IntegrationActionHandler handler) {
        IntegrationActionHandler existing = byType.putIfAbsent(handler.integrationType(), handler);
        if (existing != null) {
            throw new IllegalStateException("Duplicate IntegrationActionHandler for type '" + handler.integrationType()
                + "': " + existing.getClass().getSimpleName() + " and " + handler.getClass().getSimpleName());
        }
    }

    /** Removes a handler previously added via {@link #registerLate} — a plugin being disabled
     *  (see {@code PluginService#setEnabled}) stops offering its type without needing a restart.
     *  No-op (not an error) if the type was never registered, so disabling twice is harmless. */
    public void unregister(String type) {
        byType.remove(type);
    }

    /** Lets a caller that might run {@link #registerLate} more than once for the same handler
     *  (e.g. {@code PluginLoader} re-asserting a plugin's handlers are registered) check first,
     *  instead of relying on {@link #registerLate}'s duplicate rejection — which exists to catch a
     *  genuine type collision between two different handlers, not to be routinely triggered by a
     *  harmless re-registration of the same one. */
    public boolean isRegistered(String type) {
        return byType.containsKey(type);
    }

    public List<IntegrationActionHandler> all() {
        return List.copyOf(byType.values());
    }

    public IntegrationActionHandler require(String type) {
        IntegrationActionHandler handler = byType.get(type);
        if (handler == null) throw new IllegalArgumentException(missingHandlerMessage(type));
        return handler;
    }

    /**
     * Builds a message for an unregistered {@code type} that names the plugin responsible when
     * this can be worked out, instead of a bare "unknown type" — almost every real Data Source
     * integration type is plugin-provided now (see the plugin migrations under {@code
     * ares-plugins/}), so an unregistered type almost always means "that plugin isn't installed
     * or isn't enabled," not a typo. Used both by {@link #require} (thrown as the step's own
     * error — see {@code WorkflowRunService.executeIntegrationCall}/{@code WorkflowStepPoller}) and
     * by {@code WorkflowGraphValidator} for the equivalent save-time check. Delegates to {@link
     * PluginMessages} so the wording is identical to every other "plugin might be missing" case in
     * the app (e.g. {@code PluginFallbackController}'s structured 404 for a plugin-owned route).
     */
    public String missingHandlerMessage(String type) {
        return PluginMessages.missingPluginMessageByPrefix(pluginRepo, "Integration type", type);
    }

    /** Runs a handler's {@link IntegrationActionHandler#start} in its OWN transaction, isolated
     *  from the caller's — {@code WorkflowRunService.start()}/{@code advance()} share one big
     *  transaction across an entire synchronous execution pass, and a handler's own start() often
     *  calls other already-{@code @Transactional} services (e.g. {@code CaidoApiTaskService.create}
     *  validating the given integration id exists) that mark that shared transaction
     *  rollback-only the moment they throw — even though {@code WorkflowRunService.executeNode}'s
     *  own try/catch means the exception never escapes and the run is meant to simply record the
     *  step as FAILED and carry on. Without this isolation, that ordinary "bad config" failure
     *  surfaces as an opaque {@code UnexpectedRollbackException} at commit time instead of a clean
     *  failed step — confirmed live while wiring the first handler (an unregistered Caido
     *  integration id). {@code REQUIRES_NEW} gives every handler this safety automatically,
     *  without each one needing its own transaction-boundary code. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long start(String type, String actionCode, Long integrationInstanceId, String scopeKind, Long scopeId, Map<String, Object> params) {
        return require(type).start(actionCode, integrationInstanceId, scopeKind, scopeId, params);
    }

    /** True when {@code type} is registered AND currently advertises {@code actionCode} — used by
     *  {@code WorkflowGraphValidator} instead of a hardcoded set, so a save-time check always
     *  reflects whatever's actually deployed. */
    public boolean hasAction(String type, String actionCode) {
        IntegrationActionHandler handler = byType.get(type);
        return handler != null && handler.describeActions().stream().anyMatch(a -> a.code().equals(actionCode));
    }

    /** True when {@code type} is registered AND declares support for {@code scopeKind} — used by
     *  {@code WorkflowGraphValidator}/{@code WorkflowRunService} instead of the old blanket
     *  "project only" restriction, so each handler's own scope applies. */
    public boolean supportsScope(String type, String scopeKind) {
        IntegrationActionHandler handler = byType.get(type);
        return handler != null && handler.supportedScopes().contains(scopeKind);
    }

    /** {@link #supportsScope} plus the handler's own finer-grained {@code isAvailableForScope}
     *  gate (see that method's doc comment) — the one check the catalog endpoint and both save-time
     *  and run-time validation should use instead of {@code supportsScope} alone. */
    public boolean isAvailable(String type, String scopeKind, Long scopeId) {
        IntegrationActionHandler handler = byType.get(type);
        return handler != null && handler.supportedScopes().contains(scopeKind)
            && handler.isAvailableForScope(scopeKind, scopeId);
    }
}
