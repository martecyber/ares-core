package com.martecyber.ares.agents;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * Public installer distribution for ares-agent. Mirrors {@code CliController} but under
 * /api/v1/agent/dist/** so SecurityConfig can keep it permitAll without exposing the
 * authenticated /api/v1/agent/heartbeat endpoint.
 */
@RestController
@RequestMapping("/api/v1/agent/dist")
public class AgentDistController {

    @Value("${ares.public-url:}")
    private String configuredPublicUrl;

    @Value("${ares.versions.agent:1.0.0-beta21}")
    private String latestAgentVersion;

    private final AgentPackageService packages;

    public AgentDistController(AgentPackageService packages) {
        this.packages = packages;
    }

    /** Public — UI uses this to highlight outdated agents in the inventory table. */
    @GetMapping(value = "/version", produces = "application/json")
    public java.util.Map<String, String> version() {
        return java.util.Map.of("latest", latestAgentVersion);
    }

    /**
     * Public — which installer formats this server can actually produce.
     * `.py` and `.zip` always work (pure Java). `.deb`/`.rpm` need fpm, `.exe` needs NSIS.
     */
    @GetMapping(value = "/formats", produces = "application/json")
    public java.util.Map<String, Boolean> formats() {
        boolean fpm  = packages.hasFpm();
        boolean nsis = packages.hasNsis();
        return java.util.Map.of(
            "py",  true,
            "zip", true,
            "deb", fpm,
            "rpm", fpm,
            "exe", nsis
        );
    }

    @GetMapping(value = "/ares_agent.py", produces = "text/x-python")
    public ResponseEntity<byte[]> script() throws IOException {
        byte[] content = new ClassPathResource("agent/ares_agent.py").getContentAsByteArray();
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ares_agent.py\"")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(content);
    }

    @GetMapping("/ares_agent.deb")
    public ResponseEntity<byte[]> deb(HttpServletRequest req) {
        return build(req, "deb", "application/vnd.debian.binary-package", "ares_agent.deb");
    }

    @GetMapping("/ares_agent.rpm")
    public ResponseEntity<byte[]> rpm(HttpServletRequest req) {
        return build(req, "rpm", "application/x-rpm", "ares_agent.rpm");
    }

    @GetMapping("/ares_agent_setup.exe")
    public ResponseEntity<byte[]> exe(HttpServletRequest req) {
        return build(req, "exe", "application/octet-stream", "ares_agent_setup.exe");
    }

    /** Portable ZIP — always available; doesn't need fpm/NSIS server-side. */
    @GetMapping("/ares_agent.zip")
    public ResponseEntity<byte[]> zip(HttpServletRequest req) {
        return build(req, "zip", "application/zip", "ares_agent.zip");
    }

    private ResponseEntity<byte[]> build(HttpServletRequest req, String format,
                                          String contentType, String filename) {
        String base = resolveBaseUrl(req);
        try {
            byte[] data = switch (format) {
                case "deb" -> packages.getDeb(base);
                case "rpm" -> packages.getRpm(base);
                case "exe" -> packages.getExe(base);
                case "zip" -> packages.getZip(base);
                default    -> throw new IllegalArgumentException("Unknown format: " + format);
            };
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(contentType))
                .body(data);
        } catch (Exception e) {
            // Same fix as CliController: ProcessBuilder.start() throws a checked
            // IOException (not RuntimeException) when fpm/makensis isn't on PATH, so the
            // "missing tool" case must be checked here, in the catch-all, or it's dead code.
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            if (msg.contains("error=2")) {
                return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                    .body(("Agent package builder not available: " + msg).getBytes());
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(msg.getBytes());
        }
    }

    private String resolveBaseUrl(HttpServletRequest req) {
        if (configuredPublicUrl != null && !configuredPublicUrl.isBlank())
            return configuredPublicUrl.stripTrailing().replaceAll("/$", "");
        String proto = coalesce(req.getHeader("X-Forwarded-Proto"), req.getScheme());
        String host  = coalesce(req.getHeader("X-Forwarded-Host"), req.getHeader("Host"));
        if (host == null) {
            int port = req.getServerPort();
            host = req.getServerName() + (port == 80 || port == 443 ? "" : ":" + port);
        }
        return proto + "://" + host;
    }

    private static String coalesce(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }
}
