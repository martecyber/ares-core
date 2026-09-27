package com.martecyber.ares.agents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds and caches platform installers for the ares-agent program. Same shape as
 * {@code CliPackageService}: fpm for .deb / .rpm and NSIS for .exe. Each package
 * includes the Python script, a wrapper, and (on Linux) the systemd unit.
 */
@Service
public class AgentPackageService {

    private static final Logger log = LoggerFactory.getLogger(AgentPackageService.class);

    @org.springframework.beans.factory.annotation.Value("${ares.versions.agent:0.1.1-beta1}")
    private String version;

    private final ConcurrentHashMap<String, byte[]> cache = new ConcurrentHashMap<>();

    public byte[] getDeb(String baseUrl) throws Exception {
        return cached("deb", baseUrl, () -> buildLinuxPackage(baseUrl, "deb"));
    }

    public byte[] getRpm(String baseUrl) throws Exception {
        return cached("rpm", baseUrl, () -> buildLinuxPackage(baseUrl, "rpm"));
    }

    public byte[] getExe(String baseUrl) throws Exception {
        return cached("exe", baseUrl, () -> buildWindowsExe(baseUrl));
    }

    /**
     * Portable ZIP bundle — pure Java, no external tooling needed. Always available even
     * when fpm/NSIS are missing (dev environments). Contains the Python script + a
     * platform-agnostic README with install instructions.
     */
    public byte[] getZip(String baseUrl) throws Exception {
        return cached("zip", baseUrl, () -> buildPortableZip(baseUrl));
    }

    /** True if the underlying packager binary is reachable on PATH. UI uses this to hide unavailable downloads. */
    public boolean hasFpm()   { return commandAvailable("fpm",      "--version"); }
    public boolean hasNsis()  { return commandAvailable("makensis", "/version"); }

    private boolean commandAvailable(String cmd, String probeArg) {
        try {
            Process p = new ProcessBuilder(cmd, probeArg).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ── builders ─────────────────────────────────────────────────────────────

    private byte[] buildLinuxPackage(String baseUrl, String format) throws Exception {
        Path tmp = Files.createTempDirectory("ares-agent-pkg-");
        try {
            Path binDir   = tmp.resolve("staging/usr/local/bin");
            Path systemd  = tmp.resolve("staging/usr/lib/systemd/system");
            Files.createDirectories(binDir);
            Files.createDirectories(systemd);

            Path pyPath = binDir.resolve("ares_agent.py");
            Files.write(pyPath, classPathBytes("agent/ares_agent.py"));
            Files.setPosixFilePermissions(pyPath, PosixFilePermissions.fromString("rwxr-xr-x"));

            Path wrapperPath = binDir.resolve("ares-agent");
            Files.writeString(wrapperPath,
                "#!/bin/sh\nexec python3 /usr/local/bin/ares_agent.py \"$@\"\n",
                StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(wrapperPath, PosixFilePermissions.fromString("rwxr-xr-x"));

            Files.write(systemd.resolve("ares-agent.service"),
                classPathBytes("agent/ares-agent.service"));

            Path outFile = tmp.resolve("ares_agent." + format);

            run(tmp,
                "fpm",
                "-s", "dir",
                "-t", format,
                "--name", "ares-agent",
                "--version", version,
                "--description", "Ares ASM remote scan agent",
                "--url", baseUrl,
                "--maintainer", "Ares ASM",
                "--depends", "python3",
                "--force",
                "-C", tmp.resolve("staging").toAbsolutePath().toString(),
                "-p", outFile.toAbsolutePath().toString(),
                ".");

            return Files.readAllBytes(outFile);
        } finally {
            deleteDir(tmp);
        }
    }

    private byte[] buildPortableZip(String baseUrl) throws Exception {
        byte[] script = classPathBytes("agent/ares_agent.py");
        String readme = """
            Ares Agent — portable bundle
            ============================

            This ZIP contains the agent script and is platform-agnostic. For .deb / .rpm /
            .exe installers the Ares server needs fpm and NSIS installed; this bundle does
            not require either.

            Prerequisites
            -------------
            * Python 3.10+
            * `requests` library (see installation note below)

            Installing the 'requests' library
            ----------------------------------
            Modern Linux distros (Debian 12+, Ubuntu 23.04+, Fedora 38+) protect the
            system Python from pip. Use ONE of these options:

              Option A — system package (preferred):
                sudo apt install python3-requests      # Debian/Ubuntu
                sudo dnf install python3-requests      # Fedora/RHEL

              Option B — user install with pip:
                python3 -m pip install --user requests
                # If you get "externally-managed-environment":
                python3 -m pip install --user --break-system-packages requests

            Install (Linux/macOS)
            ---------------------
            1. Copy ares_agent.py somewhere on $PATH, e.g.
                 sudo cp ares_agent.py /usr/local/bin/ares_agent.py
                 sudo chmod +x /usr/local/bin/ares_agent.py
            2. Optional shell wrapper at /usr/local/bin/ares-agent:
                 #!/bin/sh
                 exec python3 /usr/local/bin/ares_agent.py "$@"
            3. In the Ares admin UI, register a new agent and copy the enrollment code.
            4. Enroll, then run the daemon:
                 ares-agent enroll --server %s --code <CODE>
                 ares-agent run

            Install (Windows)
            -----------------
            1. Copy ares_agent.py to e.g. C:\\Program Files\\AresAgent\\
            2. Create a `ares-agent.cmd` wrapper next to it:
                 @echo off
                 python "%%~dp0ares_agent.py" %%*
            3. Add the folder to PATH (System → Environment Variables).
            4. In the Ares admin UI, register a new agent and copy the enrollment code.
            5. Enroll and run:
                 ares-agent enroll --server %s --code <CODE>
                 ares-agent run

            Systemd unit (Linux)
            --------------------
            A reference unit file is included as `ares-agent.service`. Drop it under
            /etc/systemd/system/ and enable with:
                 sudo systemctl enable --now ares-agent
            """.formatted(baseUrl, baseUrl);

        byte[] systemd;
        try { systemd = classPathBytes("agent/ares-agent.service"); }
        catch (IOException e) { systemd = new byte[0]; }

        ByteArrayOutputStream buf = new ByteArrayOutputStream(64 * 1024);
        try (ZipOutputStream zip = new ZipOutputStream(buf)) {
            zip.putNextEntry(new ZipEntry("ares_agent.py"));
            zip.write(script);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("INSTALL.txt"));
            zip.write(readme.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            if (systemd.length > 0) {
                zip.putNextEntry(new ZipEntry("ares-agent.service"));
                zip.write(systemd);
                zip.closeEntry();
            }
        }
        return buf.toByteArray();
    }

    private byte[] buildWindowsExe(String baseUrl) throws Exception {
        Path tmp = Files.createTempDirectory("ares-agent-pkg-");
        try {
            Files.write(tmp.resolve("ares_agent.py"), classPathBytes("agent/ares_agent.py"));

            String template = new String(classPathBytes("agent/install.nsi.template"), StandardCharsets.UTF_8);
            String nsi = template
                .replace("{{version}}", version)
                .replace("{{SERVER_URL}}", baseUrl);
            Files.writeString(tmp.resolve("installer.nsi"), nsi, StandardCharsets.UTF_8);

            run(tmp, "makensis", "-V2", "installer.nsi");

            return Files.readAllBytes(tmp.resolve("ares_agent_setup.exe"));
        } finally {
            deleteDir(tmp);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private byte[] cached(String format, String baseUrl, Builder fn) throws Exception {
        String key = format + ":" + baseUrl;
        byte[] hit = cache.get(key);
        if (hit != null) return hit;
        byte[] built = fn.build();
        cache.put(key, built);
        return built;
    }

    private byte[] classPathBytes(String path) throws IOException {
        return new ClassPathResource(path).getContentAsByteArray();
    }

    private void run(Path workDir, String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(workDir.toFile()).redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = p.waitFor();
        if (rc != 0)
            throw new RuntimeException("Command failed (exit " + rc + "): " + Arrays.toString(cmd) + "\n" + out);
        if (!out.isBlank()) log.debug("[{}] {}", cmd[0], out.strip());
    }

    private void deleteDir(Path dir) {
        try {
            Files.walk(dir).sorted(Comparator.reverseOrder())
                 .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    @FunctionalInterface
    private interface Builder { byte[] build() throws Exception; }
}
