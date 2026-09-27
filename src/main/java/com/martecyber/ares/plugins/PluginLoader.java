package com.martecyber.ares.plugins;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.agents.tasks.AgentToolSpec;
import com.martecyber.ares.agents.tasks.AgentToolSpecRegistry;
import com.martecyber.ares.detections.DetectionUrlReferenceExtractor;
import com.martecyber.ares.detections.DetectionUrlReferenceParser;
import com.martecyber.ares.imports.ImportParser;
import com.martecyber.ares.imports.ImportService;
import com.martecyber.ares.integrations.IntegrationService;
import com.martecyber.ares.integrations.tools.IntegrationClient;
import com.martecyber.ares.workflows.integrations.IntegrationActionHandler;
import com.martecyber.ares.workflows.integrations.IntegrationActionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Loads a plugin JAR's classes and registers its extension-point contributions — {@link
 * IntegrationActionHandler}(s), {@link IntegrationClient}(s), any plugin-defined {@link
 * PluginExtensionRegistry} points, any internal {@link PluginComponent}(s), {@link
 * PluginRestController}s, and an optional {@link PluginLifecycle} hook — with the app. This is the
 * runtime half of the plugin system; {@link
 * PluginService} owns install/enable/disable/uninstall lifecycle and calls into this class to
 * actually make a plugin's code live (or stop being live) inside the running app. See {@link
 * PluginClassLoader}'s own doc comment for the classloading strategy and {@code plugin.json}'s
 * manifest shape in {@link PluginManifest}.
 *
 * <p>Every extension point is discovered via the standard {@code
 * META-INF/services/&lt;interface&gt;} convention — a plugin only needs to provide the file for
 * whichever interface(s) it actually implements — but NOT via {@link java.util.ServiceLoader}
 * itself: see {@link PluginClassLoader#readOwnServiceFile}'s own doc comment for why {@code
 * ServiceLoader.load(...)} cannot be used here (it instantiates each listed class itself,
 * requiring a no-arg constructor no real plugin handler has). This class reads the provider-
 * configuration file as plain text instead and hands the resolved {@code Class} to Spring for
 * constructor injection via {@link AutowireCapableBeanFactory#createBean}.
 *
 * <p>Four operations, each for a different lifecycle transition — deliberately not one method:
 * <ul>
 *   <li>{@link #load} — idempotent enable: reuses this JVM lifetime's already-loaded classes for
 *       {@code pluginId} if there are any (a prior {@link #unload}), otherwise loads fresh. Used
 *       by boot-time restore and {@code PluginService#setEnabled}.
 *   <li>{@link #install} — always loads fresh from the given JAR, REPLACING any previous
 *       registration for the same {@code pluginId} — used for a first install AND for installing
 *       a different version over an existing one. The new JAR is fully loaded and validated
 *       BEFORE anything about a previous registration is touched, so a bad new version never
 *       leaves {@code pluginId} unregistered — the old one keeps serving until the new one proves
 *       it can load. Also the only operation that runs the plugin's {@link PluginLifecycle#onInstall}.
 *   <li>{@link #unload} — disable: removes the registration but keeps the loaded classes/bean
 *       instances resident, so a later {@link #load} is instant. Never touches {@link
 *       PluginLifecycle} — a disabled plugin's data/schema must survive re-enabling untouched.
 *   <li>{@link #forget} — final removal (uninstall with nothing replacing it): removes the
 *       registration, drops the cached classes/instances, and runs {@link
 *       PluginLifecycle#onForget}.
 * </ul>
 * None of these ever calls {@code classLoader.close()} — a plugin's {@link PluginClassLoader} is
 * simply dereferenced (from {@link #loaded}) once nothing above still points to it, and the JVM
 * reclaims it through ordinary GC once nothing (including, briefly, an in-flight {@code
 * CompletableFuture} from a handler's own {@code start()}) still holds a reference — never forced
 * mid-flight. This is also why {@code PluginService} can delete an uninstalled/replaced plugin's
 * JAR file from disk immediately rather than waiting for a restart: the classloader keeps its own
 * file handle open independently of the directory entry (safe unlink-while-open semantics — this
 * assumes a Linux-style filesystem, true for every deployment target this app ships on).
 *
 * <p><b>Dependency ordering is the caller's responsibility</b> — {@link #load}/{@link #install}
 * assume every plugin id in {@link PluginManifest#dependsOn} is already present in {@link #loaded}
 * (see {@code PluginService}'s topological boot-time ordering and its install-time "dependency
 * must already be installed+enabled" check) and throw a clear error otherwise rather than trying
 * to load out of order.
 *
 * <p><b>A plugin-defined class can never be the target of a Spring AOP proxy</b> — not
 * {@code @Transactional}, {@code @Async}, {@code @Cacheable}, {@code @PreAuthorize}/{@code
 * @Secured}, or any other declarative aspect applied directly to a plugin class that implements
 * no interface (constructor-injecting a plugin bean into CORE code is fine; this only bites a
 * plugin's OWN class needing to be wrapped). Spring's proxy-creation infrastructure
 * ({@code AbstractAutoProxyCreator}, shared by all of the above) resolves its {@code
 * proxyClassLoader} exactly once, via {@code BeanClassLoaderAware}, at application-context
 * startup — long before any plugin loads — and never re-derives it afterwards, so CGLIB ends up
 * trying to generate a subclass proxy using a classloader that has never heard of the plugin
 * class it's supposed to be subclassing. This fails loudly and specifically: {@code
 * ClassNotFoundException: <TheClass>$$SpringCGLIB$$0}. Setting the thread's context classloader
 * around {@link #loadClasses} (which this class already does, for anything ELSE that falls back
 * to it) does not help — {@code AbstractAutoProxyCreator} never consults it. A plugin needing one
 * of these concerns has to reach for the programmatic equivalent instead: {@code
 * TransactionTemplate} for {@code @Transactional}, an explicitly constructor-injected {@code
 * Executor} for {@code @Async}, an inline {@code Authentication}-based role check for {@code
 * @PreAuthorize} (see {@code ares-plugin-bughunting}'s own {@code BugHuntingProgramService}/{@code
 * BugHuntingSyncJobHandler}/{@code BugHuntingProgramController} for worked examples of all three).
 * {@code @Scheduled} is the one exception that's actually safe as-is — {@code
 * ScheduledAnnotationBeanPostProcessor} invokes the annotated method via plain reflection on the
 * raw bean instance, no proxy involved.
 *
 * <p><b>A plugin contributes data to the UI, never code</b> — there is no mechanism for a plugin
 * to ship its own frontend bundle, so anything a plugin wants to show up in ares-ui has to be a
 * structured API response a generic, already-existing core component renders, not a hardcoded
 * frontend fact. The established shape: a plugin-owned identifier (an integration type, a
 * project-type code, ...) is looked up against the {@code plugin} table (usually by matching it
 * to that row's {@code pluginId} — see {@code IntegrationController#types} and {@code
 * ProjectTypeController}'s {@code pluginDisplayName}/{@code pluginIcon} fields for the core-side
 * join pattern) or, when the data doesn't live in a core-owned table at all, the plugin exposes
 * its own small {@link PluginRestController} endpoint instead (see {@code ares-plugin-bughunting}'s
 * {@code GET /api/v1/bug-hunting/platforms}, which a dependent plugin's contributions flow into
 * automatically via its own {@link PluginExtensionRegistry}). Either way the frontend fetches a
 * list, renders it generically, and shows nothing at all once the contributing plugin is gone —
 * never a plugin name/id typed directly into a `.vue` file. A handful of genuinely bespoke views
 * (a plugin's own multi-field settings form, a custom status dashboard, ...) don't fit this
 * pattern and still have to ship as literal ares-ui code today; that gap is real and unsolved,
 * not something this convention pretends to cover.
 */
@Component
public class PluginLoader {

    private static final Logger log = LoggerFactory.getLogger(PluginLoader.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** {@code AbstractHandlerMethodMapping#getMappingForMethod} — protected, no public
     *  equivalent exists (unlike {@code registerMapping}/{@code unregisterMapping}, both public
     *  precisely for this dynamic-registration use case) — reached via reflection rather than by
     *  replacing the app's autoconfigured {@code RequestMappingHandlerMapping} bean with a
     *  subclass, which would risk subtly diverging from Spring Boot's own MVC wiring for the
     *  entire existing REST API surface. */
    private static final Method GET_MAPPING_FOR_METHOD;
    static {
        try {
            GET_MAPPING_FOR_METHOD = org.springframework.web.servlet.handler.AbstractHandlerMethodMapping.class
                .getDeclaredMethod("getMappingForMethod", Method.class, Class.class);
            GET_MAPPING_FOR_METHOD.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final ConfigurableApplicationContext applicationContext;
    private final IntegrationActionRegistry actionRegistry;
    private final IntegrationService integrationService;
    private final RequestMappingHandlerMapping requestMappingHandlerMapping;
    private final JdbcTemplate jdbcTemplate;
    private final ImportService importService;
    private final AgentToolSpecRegistry agentToolSpecRegistry;
    private final DetectionUrlReferenceExtractor urlReferenceExtractor;

    private final Map<String, LoadedPlugin> loaded = new ConcurrentHashMap<>();
    /** Suffixes every Spring bean name registered below, so replacing a plugin never collides
     *  with the previous load's still-registered singleton of the same simple class name — {@code
     *  DefaultListableBeanFactory#registerSingleton} throws on a name reused while still bound. */
    private final AtomicLong loadGeneration = new AtomicLong();
    /** Disambiguates two DISTINCT bean instances of the same class registered within the very
     *  same generation — e.g. a class implementing both a core SPI (discovered in step 2) and a
     *  dependency-provided extension point (step 3) gets a separate {@code createBean()} instance
     *  per discovery path, and {@code generation} alone (shared by every registration in one
     *  {@link #loadClasses} call) can't tell those two apart. Global rather than per-generation —
     *  simplicity over strict minimality, since it only needs to be unique, not small. */
    private final AtomicLong beanSequence = new AtomicLong();

    /** One instance contributed by a dependent plugin into a dependency's own {@link
     *  PluginExtensionRegistry} — tracked so {@link #unload}/{@link #forget} can call {@link
     *  PluginExtensionRegistry#unregister} symmetrically, without the dependent plugin needing to
     *  remember which registry it came from. */
    private record ExtensionContribution(PluginExtensionRegistry<Object> registry, Object instance) {}

    /** One dynamically-discovered REST endpoint — kept as the (info, handler, method) triple
     *  {@code registerMapping}/{@code unregisterMapping} both need, not just the info, so a
     *  disable-then-re-enable cycle ({@link #unload} then {@link #load}) can register it again
     *  without re-running {@link #loadClasses} (which only ever runs once per fresh load). {@code
     *  info} is reused as-is across cycles rather than rebuilt — it's an immutable, equals/hashCode
     *  correct key {@code RequestMappingHandlerMapping} uses internally either way. */
    private record ControllerMapping(RequestMappingInfo info, Object handler, Method method) {}

    private record LoadedPlugin(PluginClassLoader classLoader,
                                 List<IntegrationActionHandler> handlers,
                                 List<IntegrationClient> clients,
                                 /** This plugin's OWN declared extension points (for dependents to use) —
                                  *  paired positionally with {@link #ownRegistries}. */
                                 List<String> providesExtensionPoints,
                                 /** extension-point FQCN → the registry bean THIS plugin provides for it. */
                                 Map<String, PluginExtensionRegistry<Object>> ownRegistries,
                                 /** Instances this plugin registered into one of ITS dependencies' registries. */
                                 List<ExtensionContribution> contributions,
                                 List<ControllerMapping> controllerMappings,
                                 List<ImportParser> importParsers,
                                 List<AgentToolSpec> agentToolSpecs,
                                 List<DetectionUrlReferenceParser> urlReferenceParsers,
                                 PluginLifecycle lifecycle) {}

    public PluginLoader(ConfigurableApplicationContext applicationContext, IntegrationActionRegistry actionRegistry,
                         IntegrationService integrationService, RequestMappingHandlerMapping requestMappingHandlerMapping,
                         JdbcTemplate jdbcTemplate, ImportService importService,
                         AgentToolSpecRegistry agentToolSpecRegistry,
                         DetectionUrlReferenceExtractor urlReferenceExtractor) {
        this.applicationContext = applicationContext;
        this.actionRegistry = actionRegistry;
        this.integrationService = integrationService;
        this.requestMappingHandlerMapping = requestMappingHandlerMapping;
        this.jdbcTemplate = jdbcTemplate;
        this.importService = importService;
        this.agentToolSpecRegistry = agentToolSpecRegistry;
        this.urlReferenceExtractor = urlReferenceExtractor;
    }

    /** Reads {@code plugin.json} from the JAR root without touching any of its classes — lets
     *  {@code PluginService} validate a manifest (and reject an incompatible/malformed one) before
     *  ever running a byte of the plugin's own code. */
    public PluginManifest readManifest(File jarFile) throws IOException {
        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry entry = jar.getJarEntry("plugin.json");
            if (entry == null) {
                throw new IllegalArgumentException("plugin.json not found at the JAR root");
            }
            try (InputStream in = jar.getInputStream(entry)) {
                return MAPPER.readValue(in, PluginManifest.class);
            }
        }
    }

    /** Idempotent enable — see class doc. */
    public synchronized void load(PluginManifest manifest, File jarFile) {
        String pluginId = manifest.id();
        LoadedPlugin lp = loaded.get(pluginId);
        if (lp == null) {
            lp = loadClasses(manifest, jarFile);
            loaded.put(pluginId, lp);
        }
        registerEverything(pluginId, lp);
    }

    /** Always-fresh load, safely replacing any previous registration for {@code pluginId} — see
     *  class doc for the ordering guarantee (new JAR proven loadable before the old one is
     *  touched). Runs {@link PluginLifecycle#onInstall} once fully wired. */
    public synchronized void install(PluginManifest manifest, File jarFile) {
        String pluginId = manifest.id();
        LoadedPlugin fresh = loadClasses(manifest, jarFile); // throws before anything else changes
        LoadedPlugin previous = loaded.put(pluginId, fresh);
        if (previous != null) {
            unregisterEverything(pluginId, previous);
            log.info("Plugin '{}': replaced previous load with a fresh one", pluginId);
        }
        registerEverything(pluginId, fresh);
        if (fresh.lifecycle() != null) {
            fresh.lifecycle().onInstall(jdbcTemplate);
        }
    }

    /** Disable: removes this plugin's registration(s) so it stops being offered — the class bytes
     *  and bean instances stay resident (see class doc), so a later {@link #load} is instant.
     *  Never calls {@link PluginLifecycle#onForget} — a disabled plugin's own data/schema must
     *  survive being re-enabled untouched. */
    public synchronized void unload(String pluginId) {
        LoadedPlugin lp = loaded.get(pluginId);
        if (lp == null) return;
        unregisterEverything(pluginId, lp);
    }

    /** Final removal (uninstall, nothing replacing it) — unlike {@link #unload}, also drops the
     *  cached classes/instances and runs {@link PluginLifecycle#onForget}, so a later {@link
     *  #load}/{@link #install} for the same {@code pluginId} (e.g. reinstalling later) starts
     *  completely fresh rather than resurrecting the old registration. Safe even if something is
     *  still mid-flight against the old handler instance — see class doc. */
    public synchronized void forget(String pluginId) {
        LoadedPlugin lp = loaded.remove(pluginId);
        if (lp == null) return;
        unregisterEverything(pluginId, lp);
        if (lp.lifecycle() != null) {
            lp.lifecycle().onForget(jdbcTemplate);
        }
    }

    public boolean isLoaded(String pluginId) {
        return loaded.containsKey(pluginId);
    }

    // ── register/unregister everything a LoadedPlugin contributed ─────────────────

    private void registerEverything(String pluginId, LoadedPlugin lp) {
        for (IntegrationActionHandler handler : lp.handlers()) {
            if (!actionRegistry.isRegistered(handler.integrationType())) {
                actionRegistry.registerLate(handler);
            }
        }
        for (IntegrationClient client : lp.clients()) {
            integrationService.registerClient(client);
        }
        for (ExtensionContribution c : lp.contributions()) {
            c.registry().register(c.instance());
        }
        for (ControllerMapping cm : lp.controllerMappings()) {
            requestMappingHandlerMapping.registerMapping(cm.info(), cm.handler(), cm.method());
        }
        for (ImportParser parser : lp.importParsers()) {
            importService.registerParser(parser);
        }
        for (DetectionUrlReferenceParser parser : lp.urlReferenceParsers()) {
            urlReferenceExtractor.registerParser(parser);
        }
        for (AgentToolSpec spec : lp.agentToolSpecs()) {
            byte[] builderBytes = null;
            if (spec.customBuilder() != null) {
                try {
                    builderBytes = lp.classLoader().readOwnResourceBytes(spec.customBuilder().module());
                } catch (Exception e) {
                    log.warn("Plugin '{}': could not read customBuilder module '{}' for '{}': {}",
                        pluginId, spec.customBuilder().module(), spec.toolId(), e.getMessage());
                }
            }
            agentToolSpecRegistry.register(spec, builderBytes);
        }
    }

    private void unregisterEverything(String pluginId, LoadedPlugin lp) {
        for (IntegrationActionHandler handler : lp.handlers()) {
            actionRegistry.unregister(handler.integrationType());
        }
        for (IntegrationClient client : lp.clients()) {
            integrationService.unregisterClient(client);
        }
        for (ExtensionContribution c : lp.contributions()) {
            c.registry().unregister(c.instance());
        }
        for (ControllerMapping cm : lp.controllerMappings()) {
            requestMappingHandlerMapping.unregisterMapping(cm.info());
        }
        for (ImportParser parser : lp.importParsers()) {
            importService.unregisterParser(parser);
        }
        for (DetectionUrlReferenceParser parser : lp.urlReferenceParsers()) {
            urlReferenceExtractor.unregisterParser(parser);
        }
        for (AgentToolSpec spec : lp.agentToolSpecs()) {
            agentToolSpecRegistry.unregister(spec);
        }
        log.debug("Plugin '{}': unregistered {} handler(s), {} client(s), {} extension contribution(s), {} route(s), {} import parser(s), {} agent tool spec(s), {} URL reference parser(s)",
            pluginId, lp.handlers().size(), lp.clients().size(), lp.contributions().size(), lp.controllerMappings().size(), lp.importParsers().size(), lp.agentToolSpecs().size(), lp.urlReferenceParsers().size());
    }

    // ── Loading ──────────────────────────────────────────────────────────────────

    private LoadedPlugin loadClasses(PluginManifest manifest, File jarFile) {
        String pluginId = manifest.id();
        Thread currentThread = Thread.currentThread();
        ClassLoader originalContextClassLoader = currentThread.getContextClassLoader();
        try {
            URL jarUrl = jarFile.toURI().toURL();

            List<PluginClassLoader> dependencyClassLoaders = new ArrayList<>();
            // Every extension-point FQCN a dependency declares, paired with the registry bean
            // that governs it — this plugin's own service-file entries for one of these FQCNs
            // get registered into that registry automatically (see step 3 below).
            List<Map.Entry<String, PluginExtensionRegistry<Object>>> availableExtensionPoints = new ArrayList<>();
            for (PluginManifest.PluginDependency dependency : manifest.dependsOn()) {
                String depId = dependency.pluginId();
                LoadedPlugin dep = loaded.get(depId);
                if (dep == null) {
                    throw new IllegalStateException("Dependency '" + depId + "' is not currently loaded — "
                        + "it must be installed and enabled before '" + pluginId + "' can load");
                }
                dependencyClassLoaders.add(dep.classLoader());
                List<String> depPoints = dep.providesExtensionPoints();
                for (int i = 0; i < depPoints.size(); i++) {
                    PluginExtensionRegistry<Object> registry = dep.ownRegistries().get(depPoints.get(i));
                    if (registry != null) availableExtensionPoints.add(Map.entry(depPoints.get(i), registry));
                }
            }

            PluginClassLoader classLoader = new PluginClassLoader(jarUrl, getClass().getClassLoader(), dependencyClassLoaders);
            // CGLIB (and anything else that falls back to Class.forName/reflection with no
            // explicit classloader — @Transactional/@Async self-proxying, @Lazy injection-point
            // proxying, ...) resolves ITS OWN default classloader from the current thread's
            // context classloader, not from the class being proxied. Left unset, that's whatever
            // classloader happened to be running this thread (the app's own, for an HTTP request
            // or main()) — which can't see a plugin-only class at all, let alone generate a
            // subclass proxy of one: fails with a bare `ClassNotFoundException` for the generated
            // proxy's own name. Every createBean() call below must run with the plugin's own
            // classloader as the thread's context classloader so any such proxy is defined
            // somewhere that can actually resolve the class being proxied.
            currentThread.setContextClassLoader(classLoader);
            AutowireCapableBeanFactory beanFactory = applicationContext.getAutowireCapableBeanFactory();
            long generation = loadGeneration.incrementAndGet();

            // 1) This plugin's OWN extension-point registries, if it declares providesExtensionPoints —
            //    paired positionally with the manifest list (see PluginExtensionRegistry's own doc).
            Map<String, PluginExtensionRegistry<Object>> ownRegistries = new LinkedHashMap<>();
            List<String> registryClassNames = classLoader.readOwnServiceFile(PluginExtensionRegistry.class.getName());
            for (int i = 0; i < registryClassNames.size() && i < manifest.providesExtensionPoints().size(); i++) {
                @SuppressWarnings("unchecked")
                Class<? extends PluginExtensionRegistry<Object>> registryClass =
                    (Class<? extends PluginExtensionRegistry<Object>>) Class.forName(registryClassNames.get(i), false, classLoader);
                PluginExtensionRegistry<Object> registryBean = beanFactory.createBean(registryClass);
                registerSingleton(pluginId, generation, registryClass, registryBean);
                String extensionPoint = manifest.providesExtensionPoints().get(i);
                ownRegistries.put(extensionPoint, registryBean);
                log.info("Plugin '{}': registered extension-point registry {} for '{}'",
                    pluginId, registryClass.getName(), extensionPoint);
            }

            // 1.5) This plugin's own internal helper beans (see PluginComponent's own doc) —
            // registered in the order listed, so later ones can constructor-inject earlier ones,
            // and so every step below (core SPI handlers, dependency-extension-point impls, REST
            // controllers, the lifecycle hook) can constructor-inject any of them by type.
            int componentCount = 0;
            for (String className : classLoader.readOwnServiceFile(PluginComponent.class.getName())) {
                Class<?> componentClass = Class.forName(className, false, classLoader);
                Object bean = beanFactory.createBean(componentClass);
                registerSingleton(pluginId, generation, componentClass, bean);
                componentCount++;
                log.info("Plugin '{}': registered internal component {}", pluginId, componentClass.getName());
            }

            // 2) Core-defined SPIs — createBean() runs the exact same lifecycle a @Component picked
            // up by classpath scan gets at startup — constructor autowiring against already-
            // registered core beans (ImportService, IntegrationService, JobService, ...) plus every
            // BeanPostProcessor — so a plugin's handler can depend on core services exactly like
            // ShodanIntegrationActionHandler/CaidoIntegrationActionHandler do today.
            List<IntegrationActionHandler> handlers = new ArrayList<>();
            for (String className : classLoader.readOwnServiceFile(IntegrationActionHandler.class.getName())) {
                @SuppressWarnings("unchecked")
                Class<? extends IntegrationActionHandler> handlerClass =
                    (Class<? extends IntegrationActionHandler>) Class.forName(className, false, classLoader);
                IntegrationActionHandler bean = beanFactory.createBean(handlerClass);
                registerSingleton(pluginId, generation, handlerClass, bean);
                handlers.add(bean);
                log.info("Plugin '{}': registered handler {} for integration type '{}'",
                    pluginId, handlerClass.getName(), bean.integrationType());
            }

            List<IntegrationClient> clients = new ArrayList<>();
            for (String className : classLoader.readOwnServiceFile(IntegrationClient.class.getName())) {
                @SuppressWarnings("unchecked")
                Class<? extends IntegrationClient> clientClass =
                    (Class<? extends IntegrationClient>) Class.forName(className, false, classLoader);
                IntegrationClient bean = beanFactory.createBean(clientClass);
                registerSingleton(pluginId, generation, clientClass, bean);
                clients.add(bean);
                log.info("Plugin '{}': registered client {} for integration type '{}'",
                    pluginId, clientClass.getName(), bean.supports());
            }

            // 2b) A third core-defined SPI, same shape as the two above — any plugin-provided
            // ImportParser is picked up by ImportService.listAvailableTools()/runImport() the
            // moment it's registered (see registerEverything()), no restart needed.
            List<ImportParser> importParsers = new ArrayList<>();
            for (String className : classLoader.readOwnServiceFile(ImportParser.class.getName())) {
                @SuppressWarnings("unchecked")
                Class<? extends ImportParser> parserClass =
                    (Class<? extends ImportParser>) Class.forName(className, false, classLoader);
                ImportParser bean = beanFactory.createBean(parserClass);
                registerSingleton(pluginId, generation, parserClass, bean);
                importParsers.add(bean);
                log.info("Plugin '{}': registered import parser {} for tool '{}' format '{}'",
                    pluginId, parserClass.getName(), bean.getToolId(), bean.getFormatId());
            }

            // A fifth core-defined SPI, same shape — optional per-tool reference-URL extraction
            // (see DetectionUrlReferenceParser's own doc). Most plugins won't have one.
            List<DetectionUrlReferenceParser> urlReferenceParsers = new ArrayList<>();
            for (String className : classLoader.readOwnServiceFile(DetectionUrlReferenceParser.class.getName())) {
                @SuppressWarnings("unchecked")
                Class<? extends DetectionUrlReferenceParser> parserClass =
                    (Class<? extends DetectionUrlReferenceParser>) Class.forName(className, false, classLoader);
                DetectionUrlReferenceParser bean = beanFactory.createBean(parserClass);
                registerSingleton(pluginId, generation, parserClass, bean);
                urlReferenceParsers.add(bean);
                log.info("Plugin '{}': registered URL reference parser {} for tool '{}'",
                    pluginId, parserClass.getName(), bean.getToolId());
            }

            // 2c) A fourth core-defined SPI, but pure data — no META-INF/services, no
            // createBean(): agent-executed tools declare themselves via a plain agent-tools.json
            // resource at the JAR root (always a JSON array, even for a plugin with exactly one
            // tool — see AgentToolSpecRegistry's own doc for why this needs no Spring bean at all).
            List<AgentToolSpec> agentToolSpecs = new ArrayList<>();
            byte[] specBytes = classLoader.readOwnResourceBytes("agent-tools.json");
            if (specBytes != null) {
                List<AgentToolSpec> parsed = MAPPER.readValue(specBytes, new TypeReference<List<AgentToolSpec>>() {});
                for (AgentToolSpec spec : parsed) {
                    log.info("Plugin '{}': registered agent tool spec '{}'", pluginId, spec.toolId());
                    if (spec.customBuilder() != null) {
                        byte[] moduleBytes = classLoader.readOwnResourceBytes(spec.customBuilder().module());
                        if (moduleBytes == null) {
                            log.warn("Plugin '{}': agent tool spec '{}' declares customBuilder module '{}', but that resource is missing from the JAR",
                                pluginId, spec.toolId(), spec.customBuilder().module());
                        } else {
                            // The JSON's own declared sha256 (if any) is never trusted — see
                            // AgentToolSpec.CustomBuilder's own doc for why this is computed here
                            // instead, straight from the bytes actually shipped in this JAR.
                            spec = spec.withCustomBuilderSha256(sha256Hex(moduleBytes));
                        }
                    }
                    agentToolSpecs.add(spec);
                }
            }

            // 3) Extension points PROVIDED BY DEPENDENCIES — this plugin may implement any of them.
            List<ExtensionContribution> contributions = new ArrayList<>();
            for (Map.Entry<String, PluginExtensionRegistry<Object>> ep : availableExtensionPoints) {
                String extensionPointFqcn = ep.getKey();
                PluginExtensionRegistry<Object> registry = ep.getValue();
                for (String className : classLoader.readOwnServiceFile(extensionPointFqcn)) {
                    Class<?> implClass = Class.forName(className, false, classLoader);
                    Object bean = beanFactory.createBean(implClass);
                    registerSingleton(pluginId, generation, implClass, bean);
                    contributions.add(new ExtensionContribution(registry, bean));
                    log.info("Plugin '{}': registered {} as an implementation of extension point '{}'",
                        pluginId, implClass.getName(), extensionPointFqcn);
                }
            }

            // 4) This plugin's own REST controllers — mappings are only BUILT here (needs the live
            // RequestMappingHandlerMapping to interpret annotations); actually registering them
            // with Spring MVC happens in registerEverything(), same as every other contribution,
            // so a later disable/re-enable cycle can re-register without re-running loadClasses().
            List<ControllerMapping> controllerMappings = new ArrayList<>();
            for (String className : classLoader.readOwnServiceFile(PluginRestController.class.getName())) {
                Class<?> controllerClass = Class.forName(className, false, classLoader);
                Object controllerBean = beanFactory.createBean(controllerClass);
                registerSingleton(pluginId, generation, controllerClass, controllerBean);
                for (Method method : controllerClass.getMethods()) {
                    RequestMappingInfo info = buildMappingInfo(method, controllerClass);
                    if (info != null) controllerMappings.add(new ControllerMapping(info, controllerBean, method));
                }
                log.info("Plugin '{}': registered REST controller {}", pluginId, controllerClass.getName());
            }

            // 5) Optional install/uninstall lifecycle hook (at most one per plugin).
            PluginLifecycle lifecycle = null;
            for (String className : classLoader.readOwnServiceFile(PluginLifecycle.class.getName())) {
                Class<?> lifecycleClass = Class.forName(className, false, classLoader);
                Object bean = beanFactory.createBean(lifecycleClass);
                registerSingleton(pluginId, generation, lifecycleClass, bean);
                lifecycle = (PluginLifecycle) bean;
                break; // at most one lifecycle bean is meaningful — extras are ignored
            }

            if (handlers.isEmpty() && clients.isEmpty() && ownRegistries.isEmpty() && componentCount == 0
                    && contributions.isEmpty() && controllerMappings.isEmpty() && importParsers.isEmpty()
                    && agentToolSpecs.isEmpty() && urlReferenceParsers.isEmpty()) {
                log.warn("Plugin '{}': JAR loaded but declares no known META-INF/services entries", pluginId);
            }
            return new LoadedPlugin(classLoader, handlers, clients, manifest.providesExtensionPoints(),
                ownRegistries, contributions, controllerMappings, importParsers, agentToolSpecs, urlReferenceParsers, lifecycle);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load plugin '" + pluginId + "': " + e.getMessage(), e);
        } finally {
            currentThread.setContextClassLoader(originalContextClassLoader);
        }
    }

    private RequestMappingInfo buildMappingInfo(Method method, Class<?> handlerType) {
        try {
            return (RequestMappingInfo) GET_MAPPING_FOR_METHOD.invoke(requestMappingHandlerMapping, method, handlerType);
        } catch (Exception e) {
            throw new IllegalStateException("Could not build a request mapping for " + method, e);
        }
    }

    private void registerSingleton(String pluginId, long generation, Class<?> beanClass, Object bean) {
        String beanName = "plugin." + pluginId + "." + generation + "." + beanSequence.incrementAndGet()
            + "." + beanClass.getSimpleName();
        applicationContext.getBeanFactory().registerSingleton(beanName, bean);
    }

    /** Same algorithm/output shape as {@code PluginService}'s own JAR-checksum helper, just
     *  over an in-memory byte array instead of a file — used to compute an {@code
     *  AgentToolSpec.CustomBuilder}'s verified sha256 (see {@link AgentToolSpec#withCustomBuilderSha256}). */
    private static String sha256Hex(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is always available on any JVM
        }
    }
}
