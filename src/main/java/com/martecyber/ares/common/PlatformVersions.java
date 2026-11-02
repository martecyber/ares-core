package com.martecyber.ares.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Component versions, read from the artifacts themselves so a release never needs a
 * hand-edited number:
 *   • api   ← this jar's build info (pom.xml revision, bumped by the release workflow)
 *   • cli   ← CLI_VERSION in the bundled cli/ares_cli.py (the script users download)
 *   • agent ← AGENT_VERSION in the bundled agent/ares_agent.py (drives agent auto-update,
 *             so it must match the script /agent/dist serves)
 * The UI shows its own build version; it isn't known server-side.
 * {@code ares.versions.<name>} still overrides any of them when set.
 */
@Component
public class PlatformVersions {

    private static final Logger log = LoggerFactory.getLogger(PlatformVersions.class);
    static final String UNKNOWN = "dev";

    private final String api;
    private final String cli;
    private final String agent;

    public PlatformVersions(ObjectProvider<BuildProperties> buildProperties,
                            @Value("${ares.versions.api:}") String apiOverride,
                            @Value("${ares.versions.cli:}") String cliOverride,
                            @Value("${ares.versions.agent:}") String agentOverride) {
        BuildProperties build = buildProperties.getIfAvailable();
        this.api = firstNonBlank(apiOverride, build != null ? build.getVersion() : null);
        this.cli = firstNonBlank(cliOverride, scriptVersion("cli/ares_cli.py", "CLI_VERSION"));
        this.agent = firstNonBlank(agentOverride, scriptVersion("agent/ares_agent.py", "AGENT_VERSION"));
    }

    public String api()   { return api; }
    public String cli()   { return cli; }
    public String agent() { return agent; }

    /** {@code NAME = "x.y.z"} at the start of a line in a bundled Python script, or null. */
    static String scriptVersion(String classpathFile, String constant) {
        try (InputStream in = new ClassPathResource(classpathFile).getInputStream()) {
            return parseConstant(new String(in.readAllBytes(), StandardCharsets.UTF_8), constant);
        } catch (Exception e) {
            log.warn("Could not read {} to determine its version: {}", classpathFile, e.getMessage());
            return null;
        }
    }

    static String parseConstant(String source, String constant) {
        Matcher m = Pattern.compile("(?m)^" + Pattern.quote(constant) + "\\s*=\\s*[\"']([^\"']+)[\"']").matcher(source);
        return m.find() ? m.group(1) : null;
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a.trim();
        if (b != null && !b.isBlank()) return b.trim();
        return UNKNOWN;
    }
}
