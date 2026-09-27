package com.martecyber.ares.cli;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Public endpoints (whitelisted under /api/v1/cli/** in SecurityConfig) backing the CLI's
 * browser-login device flow. The user-facing approve/deny endpoints that require a logged-in
 * session live separately in {@link CliDeviceApprovalController}, deliberately under a
 * different path prefix so they fall under the default "authenticated" rule instead of this
 * package's permitAll.
 */
@RestController
@RequestMapping("/api/v1/cli/device")
public class CliDeviceController {

    @Value("${ares.public-url:}")
    private String configuredPublicUrl;

    private final CliDeviceAuthService svc;

    public CliDeviceController(CliDeviceAuthService svc) { this.svc = svc; }

    public record StartRequest(String clientInfo) {}

    @PostMapping("/start")
    public Map<String, Object> start(@RequestBody(required = false) StartRequest req, HttpServletRequest httpReq) {
        CliDeviceAuth a = svc.start(req != null ? req.clientInfo() : null);
        String base = resolveBaseUrl(httpReq);
        return Map.of(
            "deviceCode", a.getDeviceCode(),
            "userCode", a.getUserCode(),
            "verificationUrl", base + "/cli-authorize?code=" + a.getDeviceCode(),
            "expiresIn", 600,
            "interval", 3
        );
    }

    @GetMapping("/info")
    public Map<String, Object> info(@RequestParam("code") String deviceCode) {
        CliDeviceAuth a = svc.info(deviceCode);
        return Map.of(
            "userCode", a.getUserCode(),
            "clientInfo", a.getClientInfo() != null ? a.getClientInfo() : "",
            "status", a.getStatus()
        );
    }

    @GetMapping("/poll")
    public Map<String, Object> poll(@RequestParam("code") String deviceCode) {
        return svc.poll(deviceCode);
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
