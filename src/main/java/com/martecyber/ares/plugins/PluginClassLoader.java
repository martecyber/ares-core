package com.martecyber.ares.plugins;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * One per loaded plugin JAR. Parent-first for the platform's own SPI/domain classes so an
 * {@code instanceof}/cast against {@code IntegrationActionHandler} (or any other {@code
 * com.martecyber.ares.*} type) always compares against the single copy the running app already
 * loaded — never a second, incompatible copy the plugin JAR might happen to bundle. Child-first
 * for everything else, so a plugin's own third-party dependencies (a different Jackson/HTTP-client
 * version, say) don't silently resolve to whatever ares-core itself happens to ship.
 *
 * <p>Plugin code must NOT live under {@code com.martecyber.ares.*} for this reason — that whole
 * prefix always resolves to the parent, so a plugin class package there would never actually load
 * from the plugin's own JAR. See the plugin authoring convention: {@code com.martecyber.plugins.*}.
 *
 * <p>{@code dependencyClassLoaders} (see {@link PluginManifest#dependsOn}): for anything not
 * found in this plugin's own JAR and not under {@code com.martecyber.ares.*}, each dependency's
 * classloader is tried in declared order before finally falling back to the app classloader (for
 * ordinary JDK/third-party classes). This is what lets, say, {@code ares-plugin-bughunting-
 * hackerone} resolve {@code com.martecyber.plugins.bughunting.BugHuntingClient} — a class defined
 * in a DIFFERENT plugin's JAR, loaded by a DIFFERENT {@code PluginClassLoader} instance — as the
 * exact same {@code Class} object every dependent plugin implementing it shares (never redefined
 * locally), so {@code instanceof}/casts against it behave correctly across plugin boundaries too.
 * {@link PluginLoader} guarantees a dependency is always loaded (and thus has a resolvable
 * classloader instance to pass in here) strictly before anything that depends on it — see its own
 * topological-order loading.
 */
final class PluginClassLoader extends URLClassLoader {

    private static final String CORE_PREFIX = "com.martecyber.ares.";

    private final List<PluginClassLoader> dependencyClassLoaders;

    PluginClassLoader(URL jarUrl, ClassLoader appClassLoader, List<PluginClassLoader> dependencyClassLoaders) {
        super(new URL[]{jarUrl}, appClassLoader);
        this.dependencyClassLoaders = dependencyClassLoaders;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                if (name.startsWith(CORE_PREFIX)) {
                    c = getParent().loadClass(name);
                } else {
                    try {
                        c = findClass(name);
                    } catch (ClassNotFoundException e) {
                        c = loadFromDependencyOrFallBack(name);
                    }
                }
            }
            if (resolve) resolveClass(c);
            return c;
        }
    }

    private Class<?> loadFromDependencyOrFallBack(String name) throws ClassNotFoundException {
        for (PluginClassLoader dependency : dependencyClassLoaders) {
            try {
                // Same class (package-private, this file), so calling another instance's own
                // protected loadClass is allowed regardless of Java's usual protected-access
                // rules — this is a lookup delegation, not a redefinition: the returned Class
                // object stays defined by `dependency`, exactly like the CORE_PREFIX branch above
                // delegates to the app classloader without ever re-defining core classes locally.
                return dependency.loadClass(name, false);
            } catch (ClassNotFoundException ignored) {
                // try the next declared dependency
            }
        }
        return super.loadClass(name, false);
    }

    /** Reads a {@code META-INF/services/<interface>} provider-configuration file's class names
     *  as plain text — deliberately NOT {@link java.util.ServiceLoader#load}, whose own iteration
     *  instantiates each listed class itself (via a public no-arg constructor, or a static {@code
     *  provider()} factory method) the moment {@code hasNext()}/{@code next()} runs, before {@link
     *  PluginLoader} ever gets a chance to hand the class to Spring for constructor injection —
     *  every real plugin handler needs core beans injected (ImportService, IntegrationService,
     *  ...), so it has no no-arg constructor and {@code ServiceLoader.load(...).iterator()} fails
     *  with exactly the {@code ServiceConfigurationError: ... Unable to get public no-arg
     *  constructor} this replaced (confirmed live, installing the first plugin to actually declare
     *  constructor-injected dependencies). {@link #findResource} — not {@link #getResource}, which
     *  would delegate to the parent first — since this must only ever see the plugin JAR's own
     *  file, never something coincidentally on the same path elsewhere in the classpath. */
    List<String> readOwnServiceFile(String serviceInterfaceName) throws IOException {
        URL url = findResource("META-INF/services/" + serviceInterfaceName);
        if (url == null) return List.of();
        List<String> names = new ArrayList<>();
        try (var in = url.openStream();
             var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int hash = line.indexOf('#');
                String trimmed = (hash >= 0 ? line.substring(0, hash) : line).trim();
                if (!trimmed.isEmpty()) names.add(trimmed);
            }
        }
        return names;
    }

    /** Reads an arbitrary resource straight out of this plugin's own JAR (never a same-path file
     *  elsewhere on the classpath — same {@link #findResource}-not-{@code getResource} rationale
     *  as {@link #readOwnServiceFile}) — used for a plugin's declarative {@code agent-tool.json}
     *  spec and, when it declares one, its optional {@code customBuilder} Python module. Returns
     *  {@code null} if the plugin doesn't have that resource, since both callers treat "absent" as
     *  a legitimate, common case rather than an error. */
    byte[] readOwnResourceBytes(String path) throws IOException {
        URL url = findResource(path);
        if (url == null) return null;
        try (var in = url.openStream(); var out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        }
    }
}
