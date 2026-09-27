package com.martecyber.ares.cli;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CliPackageService {

    private static final Logger log = LoggerFactory.getLogger(CliPackageService.class);

    private static final Pattern VERSION_PATTERN = Pattern.compile("(?m)^version\\s*=\\s*\"([^\"]+)\"");

    /** Parsed once at startup from cli/pyproject.toml — the CLI's own version file is the
     *  single source of truth, replacing a previously hardcoded/never-actually-configured
     *  ares.versions.cli property that silently drifted two releases behind. */
    private String version = "0.0.0";

    private final ConcurrentHashMap<String, byte[]> cache = new ConcurrentHashMap<>();

    @PostConstruct
    void loadVersion() {
        try {
            String toml = new String(classPathBytes("cli/pyproject.toml"), StandardCharsets.UTF_8);
            Matcher m = VERSION_PATTERN.matcher(toml);
            if (m.find()) {
                version = m.group(1);
            } else {
                log.warn("No version = \"...\" line found in cli/pyproject.toml — CLI packages will report version {}", version);
            }
        } catch (IOException e) {
            log.warn("Could not read cli/pyproject.toml — CLI packages will report version {}: {}", version, e.getMessage());
        }
    }

    public String getVersion() {
        return version;
    }

    public byte[] getDeb(String baseUrl) throws Exception {
        return cached("deb", baseUrl, () -> buildLinuxPackage(baseUrl, "deb"));
    }

    public byte[] getRpm(String baseUrl) throws Exception {
        return cached("rpm", baseUrl, () -> buildLinuxPackage(baseUrl, "rpm"));
    }

    public byte[] getExe(String baseUrl) throws Exception {
        return cached("exe", baseUrl, () -> buildWindowsExe(baseUrl));
    }

    // ── builders ─────────────────────────────────────────────────────────────

    private byte[] buildLinuxPackage(String baseUrl, String format) throws Exception {
        Path tmp = Files.createTempDirectory("ares-pkg-");
        try {
            Path binDir = tmp.resolve("staging/usr/local/bin");
            Path cfgDir = tmp.resolve("staging/etc/ares");
            Files.createDirectories(binDir);
            Files.createDirectories(cfgDir);

            Path pyPath = binDir.resolve("ares_cli.py");
            Files.write(pyPath, classPathBytes("cli/ares_cli.py"));
            Files.setPosixFilePermissions(pyPath, PosixFilePermissions.fromString("rwxr-xr-x"));

            Path wrapperPath = binDir.resolve("ares");
            Files.writeString(wrapperPath,
                "#!/bin/sh\nexec python3 /usr/local/bin/ares_cli.py \"$@\"\n",
                StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(wrapperPath, PosixFilePermissions.fromString("rwxr-xr-x"));

            Files.writeString(cfgDir.resolve("config.json"),
                "{\"server\":\"" + baseUrl + "\"}\n", StandardCharsets.UTF_8);

            Path outFile = tmp.resolve("ares_cli." + format);

            // ares_cli.py is pure Python with no compiled binaries, so it runs identically on
            // any CPU architecture — but fpm defaults to tagging the package with the *build
            // machine's* native architecture (confirmed: amd64 for .deb, x86_64 for .rpm) if
            // not told otherwise. That's actively wrong, not just imprecise: dpkg/rpm both
            // refuse to install a package whose declared architecture doesn't match the local
            // machine, so an untagged build would silently fail to install on e.g. ARM64
            // Debian/Raspberry Pi OS even though the contents would run there just fine.
            // "all" (deb) / "noarch" (rpm) are the correct, standard tags for an
            // architecture-independent package — verified against real dpkg-deb/rpm output.
            String archFlag = "deb".equals(format) ? "all" : "noarch";

            run(tmp,
                "fpm",
                "-s", "dir",
                "-t", format,
                "--name", "ares-cli",
                "--version", version,
                "--architecture", archFlag,
                "--description", "Ares ASM CLI",
                "--url", baseUrl,
                "--maintainer", "Marte Cyber",
                "--vendor", "Marte Cyber",
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

    private byte[] buildWindowsExe(String baseUrl) throws Exception {
        Path tmp = Files.createTempDirectory("ares-pkg-");
        try {
            Files.write(tmp.resolve("ares_cli.py"), classPathBytes("cli/ares_cli.py"));

            String template = new String(classPathBytes("cli/install.nsi.template"), StandardCharsets.UTF_8);
            // Template placeholder is "{{VERSION}}" (uppercase) — a prior lowercase
            // "{{version}}" here never matched it, so the built installer's version string
            // was always the literal text "{{VERSION}}" instead of the real version. Never
            // noticed because makensis isn't installed anywhere yet (.exe returns 501).
            String nsi = template
                .replace("{{VERSION}}", version)
                .replace("{{SERVER_URL}}", baseUrl);
            Files.writeString(tmp.resolve("installer.nsi"), nsi, StandardCharsets.UTF_8);

            run(tmp, "makensis", "-V2", "installer.nsi");

            return Files.readAllBytes(tmp.resolve("ares_cli_setup.exe"));
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
        ProcessBuilder pb = new ProcessBuilder(cmd)
            .directory(workDir.toFile())
            .redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int rc = p.waitFor();
        if (rc != 0)
            throw new RuntimeException("Command failed (exit " + rc + "): " + Arrays.toString(cmd) + "\n" + out);
        if (!out.isBlank()) log.debug("[{}] {}", cmd[0], out.strip());
    }

    private void deleteDir(Path dir) {
        try {
            Files.walk(dir)
                 .sorted(Comparator.reverseOrder())
                 .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }

    @FunctionalInterface
    private interface Builder {
        byte[] build() throws Exception;
    }
}
