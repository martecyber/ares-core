package com.martecyber.ares.plugins;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.agents.tasks.AgentToolSpecRegistry;
import com.martecyber.ares.imports.ImportService;
import com.martecyber.ares.integrations.IntegrationService;
import com.martecyber.ares.workflows.integrations.IntegrationActionHandler;
import com.martecyber.ares.workflows.integrations.IntegrationActionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Exercises real classloading + real Spring bean instantiation end to end — every other plugin
 * test ({@link PluginServiceTest}) mocks {@link PluginLoader} itself, so none of them could ever
 * have caught the bug this test is built around: {@code PluginLoader} originally discovered
 * handler classes via {@code ServiceLoader.load(IntegrationActionHandler.class, classLoader)} and
 * iterated it directly — but {@code ServiceLoader}'s own iteration instantiates each provider
 * itself via a public no-arg constructor, which no real constructor-injected handler has, so
 * every actual plugin install failed with {@code ServiceConfigurationError: ... Unable to get
 * public no-arg constructor} the moment a handler needing real dependencies (as opposed to the
 * pilot's earlier, never-actually-installed builds) was installed against a live instance. Fixed
 * by reading the {@code META-INF/services} file as plain text ({@link
 * PluginClassLoader#readOwnServiceFile}) and handing the resolved {@code Class} to Spring's {@code
 * AutowireCapableBeanFactory#createBean} instead of letting {@code ServiceLoader} instantiate it.
 */
class PluginLoaderTest {

    /** A real, non-mocked bean factory — {@code createBean()} on this performs genuine
     *  constructor-autowiring resolution, so a regression back to {@code ServiceLoader}'s own
     *  no-arg-only instantiation would fail this test exactly like it failed in production. */
    @Test
    void loadsAPluginHandlerRequiringConstructorInjectionOfACoreBean(@TempDir Path tempDir) throws Exception {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("objectMapper", new ObjectMapper());

        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        when(context.getAutowireCapableBeanFactory()).thenReturn(beanFactory);
        when(context.getBeanFactory()).thenReturn(beanFactory);

        IntegrationActionRegistry actionRegistry = mock(IntegrationActionRegistry.class);
        IntegrationService integrationService = mock(IntegrationService.class);
        PluginLoader loader = new PluginLoader(context, actionRegistry, integrationService,
            mock(RequestMappingHandlerMapping.class), mock(JdbcTemplate.class), mock(ImportService.class),
            mock(AgentToolSpecRegistry.class), mock(com.martecyber.ares.detections.DetectionUrlReferenceExtractor.class));

        File jar = buildFixtureJar(tempDir, "v1", "test-plugin-type");
        loader.load(loader.readManifest(jar), jar);

        ArgumentCaptor<IntegrationActionHandler> captor = ArgumentCaptor.forClass(IntegrationActionHandler.class);
        verify(actionRegistry).registerLate(captor.capture());
        assertEquals("test-plugin-type", captor.getValue().integrationType());
        // Proves real constructor injection happened, not just that SOME instance was built —
        // the fixture class throws from its own constructor if the injected ObjectMapper is null.

        assertTrue(loader.isLoaded("test-plugin"));
        verifyNoInteractions(integrationService); // fixture declares no IntegrationClient
    }

    /** Regression coverage for the bean-name-collision bug {@code install()}'s replace path would
     *  hit without a unique-per-load suffix: {@code
     *  DefaultListableBeanFactory#registerSingleton} throws if a name is reused while still bound,
     *  and installing "v2" over "v1" reuses the exact same simple class name ({@code
     *  FixtureHandler}) — the two fixture builds only differ in their compiled package (a fresh
     *  temp dir per build), the real-world equivalent of a plugin author bumping the version
     *  without renaming the class. Also proves the new JAR is loaded and registered BEFORE the old
     *  one is unregistered (see {@code PluginLoader#install}'s own doc comment): if that ordering
     *  regressed to "unregister old, then load new", a failure loading "new" would leave nothing
     *  registered at all instead of leaving "v1" in place — not exercised by throwing here (this
     *  fixture always loads successfully), but the final registry state after install() below is
     *  exactly what that ordering guarantees. */
    @Test
    void installReplacesAPreviousVersionUnderTheSamePluginIdWithoutABeanNameCollision(@TempDir Path tempDir) throws Exception {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("objectMapper", new ObjectMapper());

        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        when(context.getAutowireCapableBeanFactory()).thenReturn(beanFactory);
        when(context.getBeanFactory()).thenReturn(beanFactory);

        IntegrationActionRegistry actionRegistry = mock(IntegrationActionRegistry.class);
        IntegrationService integrationService = mock(IntegrationService.class);
        PluginLoader loader = new PluginLoader(context, actionRegistry, integrationService,
            mock(RequestMappingHandlerMapping.class), mock(JdbcTemplate.class), mock(ImportService.class),
            mock(AgentToolSpecRegistry.class), mock(com.martecyber.ares.detections.DetectionUrlReferenceExtractor.class));

        File v1 = buildFixtureJar(tempDir.resolve("v1"), "v1", "test-plugin-type-v1");
        File v2 = buildFixtureJar(tempDir.resolve("v2"), "v2", "test-plugin-type-v2");

        loader.install(loader.readManifest(v1), v1);
        loader.install(loader.readManifest(v2), v2); // same pluginId, same FixtureHandler simple name — must not throw

        verify(actionRegistry).unregister("test-plugin-type-v1");
        verify(actionRegistry).registerLate(argThat(h -> h.integrationType().equals("test-plugin-type-v2")));
        verify(actionRegistry, never()).unregister("test-plugin-type-v2");
    }

    /** Unlike every other SPI this class exercises, an {@code AgentToolSpec} needs no compiled
     *  Java class at all — just the plain {@code agent-tools.json} data resource — so this fixture
     *  doesn't bother invoking the compiler. */
    @Test
    void loadsAPluginAgentToolSpecAndRegistersUnregistersIt(@TempDir Path tempDir) throws Exception {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        when(context.getAutowireCapableBeanFactory()).thenReturn(beanFactory);
        when(context.getBeanFactory()).thenReturn(beanFactory);

        com.martecyber.ares.agents.tasks.AgentToolSpecRegistry registry =
            new com.martecyber.ares.agents.tasks.AgentToolSpecRegistry();
        PluginLoader loader = new PluginLoader(context, mock(IntegrationActionRegistry.class),
            mock(IntegrationService.class), mock(RequestMappingHandlerMapping.class),
            mock(JdbcTemplate.class), mock(ImportService.class), registry,
            mock(com.martecyber.ares.detections.DetectionUrlReferenceExtractor.class));

        File jarFile = tempDir.resolve("fixture-agent-tool-plugin.jar").toFile();
        try (JarOutputStream jar = new JarOutputStream(new FileOutputStream(jarFile))) {
            addEntry(jar, "plugin.json", """
                {"id":"test-agent-plugin","version":"1.0.0","displayName":"Test Agent Plugin","sdkVersion":"1"}
                """);
            addEntry(jar, "agent-tools.json", """
                [{"toolId":"testtool","displayName":"Test Tool","binary":"testtool",
                  "resultFormat":"default","validScopeKinds":[],"validAssetTypes":[],
                  "maxTargets":null,"targetEmission":"positional","minVersion":null,
                  "requiresRoot":false,"customBuilder":null,"fields":[]}]
                """);
        }

        long versionBefore = registry.version();
        loader.load(loader.readManifest(jarFile), jarFile);
        assertTrue(registry.find("testtool").isPresent());
        assertEquals("Test Tool", registry.find("testtool").get().displayName());
        assertTrue(registry.version() > versionBefore);

        loader.unload("test-agent-plugin");
        assertTrue(registry.find("testtool").isEmpty());
    }

    /** Compiles a tiny {@code IntegrationActionHandler} implementation (constructor-injecting
     *  {@link ObjectMapper}, deliberately no no-arg constructor) against this test's own
     *  classpath, and packages it into a real JAR with the {@code META-INF/services} entry and
     *  {@code plugin.json} a real plugin ships — using the plugin-authoring package prefix ({@code
     *  com.martecyber.plugins.*}, never {@code com.martecyber.ares.*}) so {@link
     *  PluginClassLoader}'s parent-first delegation doesn't try to resolve it from the app
     *  classloader, where it was never compiled. {@code buildTag} picks the temp subdirectory (so
     *  two builds in the same test — e.g. an "upgrade" scenario — don't clobber each other's
     *  compiled classes); {@code type} becomes the fixture handler's own {@code integrationType()}. */
    private File buildFixtureJar(Path tempDir, String buildTag, String type) throws IOException {
        Path srcDir = tempDir.resolve("src-" + buildTag);
        Path pkgDir = srcDir.resolve("com/martecyber/plugins/testfixture");
        Files.createDirectories(pkgDir);
        Path javaFile = pkgDir.resolve("FixtureHandler.java");
        Files.writeString(javaFile, """
            package com.martecyber.plugins.testfixture;

            import com.fasterxml.jackson.databind.ObjectMapper;
            import com.martecyber.ares.workflows.integrations.*;
            import java.util.List;
            import java.util.Map;
            import java.util.Set;

            public class FixtureHandler implements IntegrationActionHandler {
                private final ObjectMapper mapper;

                public FixtureHandler(ObjectMapper mapper) {
                    if (mapper == null) throw new IllegalStateException("ObjectMapper was not injected");
                    this.mapper = mapper;
                }

                @Override public String integrationType() { return "%s"; }
                @Override public String integrationTypeLabel() { return "Test Plugin"; }
                @Override public Set<String> supportedScopes() { return Set.of("project"); }
                @Override public List<IntegrationActionDescriptor> describeActions() { return List.of(); }
                @Override public List<IntegrationInstanceDescriptor> listInstances(String scopeKind, Long scopeId) { return List.of(); }
                @Override public Long start(String actionCode, Long integrationInstanceId, String scopeKind, Long scopeId, Map<String,Object> params) { return 1L; }
                @Override public IntegrationActionResult checkStatus(Long refId) {
                    return new IntegrationActionResult(IntegrationActionResult.COMPLETED, "{}", null);
                }
            }
            """.formatted(type));

        Path classesDir = tempDir.resolve("classes-" + buildTag);
        Files.createDirectories(classesDir);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        String classpath = System.getProperty("java.class.path");
        int result = compiler.run(null, null, null,
            "-cp", classpath, "-d", classesDir.toString(), javaFile.toString());
        if (result != 0) throw new IllegalStateException("Fixture compilation failed");

        File jarFile = tempDir.resolve("fixture-plugin-" + buildTag + ".jar").toFile();
        try (JarOutputStream jar = new JarOutputStream(new FileOutputStream(jarFile))) {
            addEntry(jar, "plugin.json", """
                {"id":"test-plugin","version":"1.0.0","displayName":"Test Plugin","sdkVersion":"1"}
                """);
            addEntry(jar, "META-INF/services/com.martecyber.ares.workflows.integrations.IntegrationActionHandler",
                "com.martecyber.plugins.testfixture.FixtureHandler\n");

            try (Stream<Path> walk = Files.walk(classesDir)) {
                for (Path classFile : walk.filter(Files::isRegularFile).toList()) {
                    String entryName = classesDir.relativize(classFile).toString().replace(File.separatorChar, '/');
                    jar.putNextEntry(new JarEntry(entryName));
                    Files.copy(classFile, jar);
                    jar.closeEntry();
                }
            }
        }
        return jarFile;
    }

    private static void addEntry(JarOutputStream jar, String name, String content) throws IOException {
        jar.putNextEntry(new JarEntry(name));
        jar.write(content.getBytes());
        jar.closeEntry();
    }
}
