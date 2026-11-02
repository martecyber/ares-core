package com.martecyber.ares.common;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformVersionsTest {

    @SuppressWarnings("unchecked")
    private static ObjectProvider<BuildProperties> build(String version) {
        ObjectProvider<BuildProperties> p = mock(ObjectProvider.class);
        if (version == null) return p;
        Properties props = new Properties();
        props.setProperty("version", version);
        when(p.getIfAvailable()).thenReturn(new BuildProperties(props));
        return p;
    }

    @Test
    void versionsComeFromTheBuildAndTheBundledScripts() {
        PlatformVersions v = new PlatformVersions(build("1.0.0-beta14"), "", "", "");
        assertEquals("1.0.0-beta14", v.api());
        // The real bundled scripts on the classpath define these constants.
        assertEquals(PlatformVersions.scriptVersion("cli/ares_cli.py", "CLI_VERSION"), v.cli());
        assertEquals(PlatformVersions.scriptVersion("agent/ares_agent.py", "AGENT_VERSION"), v.agent());
        assertNotEquals(PlatformVersions.UNKNOWN, v.cli());
        assertNotEquals(PlatformVersions.UNKNOWN, v.agent());
    }

    @Test
    void explicitPropertiesOverrideAndMissingBuildInfoFallsBack() {
        PlatformVersions v = new PlatformVersions(build(null), " ", "9.9.9", "8.8.8");
        assertEquals(PlatformVersions.UNKNOWN, v.api());
        assertEquals("9.9.9", v.cli());
        assertEquals("8.8.8", v.agent());
    }

    @Test
    void parsesTheConstantAtLineStartOnly() {
        String src = "x = 1\n# AGENT_VERSION = \"0.0.1\"\nAGENT_VERSION = \"1.2.3-beta4\"\n";
        assertEquals("1.2.3-beta4", PlatformVersions.parseConstant(src, "AGENT_VERSION"));
        assertNull(PlatformVersions.parseConstant("nothing here", "AGENT_VERSION"));
    }
}
