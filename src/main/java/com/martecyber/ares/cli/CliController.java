package com.martecyber.ares.cli;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/cli")
public class CliController {

    @Value("${ares.public-url:}")
    private String configuredPublicUrl;

    @Autowired
    private CliPackageService packages;

    /** Lets installed CLIs check whether they're up to date (`ares update` / the
     *  auto-check on every run) without needing to download a whole package first. */
    @GetMapping("/version")
    public Map<String, String> version() {
        return Map.of("version", packages.getVersion());
    }

    /** Serves the Python CLI source for downloaders and install scripts. */
    @GetMapping(value = "/ares_cli.py", produces = "text/x-python")
    public ResponseEntity<byte[]> script() throws IOException {
        byte[] content = new ClassPathResource("cli/ares_cli.py").getContentAsByteArray();
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ares_cli.py\"")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(content);
    }

    /** Bash installer for Linux and macOS. */
    @GetMapping(value = "/install.sh", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> installSh(HttpServletRequest req) {
        String base = resolveBaseUrl(req);
        return attachment("install.sh", buildBashScript(base));
    }

    /** PowerShell installer for Windows. */
    @GetMapping(value = "/install.ps1", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> installPs1(HttpServletRequest req) {
        String base = resolveBaseUrl(req);
        return attachment("install.ps1", buildPowerShellScript(base));
    }

    /** Debian/Ubuntu package (.deb). */
    @GetMapping("/ares_cli.deb")
    public ResponseEntity<byte[]> deb(HttpServletRequest req) {
        return buildPackage(req, "deb", "application/vnd.debian.binary-package", "ares_cli.deb");
    }

    /** RPM package for RHEL/Fedora/SUSE (.rpm). */
    @GetMapping("/ares_cli.rpm")
    public ResponseEntity<byte[]> rpm(HttpServletRequest req) {
        return buildPackage(req, "rpm", "application/x-rpm", "ares_cli.rpm");
    }

    /** Windows installer (.exe, built with NSIS). */
    @GetMapping("/ares_cli_setup.exe")
    public ResponseEntity<byte[]> exe(HttpServletRequest req) {
        return buildPackage(req, "exe", "application/octet-stream", "ares_cli_setup.exe");
    }

    private ResponseEntity<byte[]> buildPackage(HttpServletRequest req,
                                                 String format,
                                                 String contentType,
                                                 String filename) {
        String base = resolveBaseUrl(req);
        try {
            byte[] data = switch (format) {
                case "deb" -> packages.getDeb(base);
                case "rpm" -> packages.getRpm(base);
                case "exe" -> packages.getExe(base);
                default    -> throw new IllegalArgumentException("Unknown format: " + format);
            };
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(contentType))
                .body(data);
        } catch (Exception e) {
            // ProcessBuilder.start() throws a checked IOException (not a RuntimeException)
            // when the builder binary (fpm/makensis) isn't on PATH — must catch Exception
            // here, not RuntimeException, or this "missing tool" case is never reached and
            // always surfaces as a raw 500 instead.
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            if (msg.contains("error=2")) {
                return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                    .body(("Package builder not available: " + msg).getBytes());
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(msg.getBytes());
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ResponseEntity<String> attachment(String filename, String body) {
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(body);
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

    // ── script templates ──────────────────────────────────────────────────────

    private String buildBashScript(String baseUrl) {
        return """
#!/usr/bin/env bash
# Ares ASM CLI — installer for Linux / macOS
# Server: %s
set -euo pipefail

ARES_SERVER="%s"
INSTALL_DIR="${HOME}/.local/bin"
CONFIG_DIR="${HOME}/.ares"

echo "▶ Checking Python 3..."
if ! command -v python3 &>/dev/null; then
  echo "✗ Python 3 not found. Install it first (https://python.org)." >&2
  exit 1
fi
PYTHON=$(command -v python3)
echo "  Using: $PYTHON ($($PYTHON --version 2>&1))"

echo "▶ Installing 'requests' library..."
if ! $PYTHON -c "import requests" 2>/dev/null; then
  if ! $PYTHON -m pip install --user --quiet requests 2>/dev/null; then
    echo "  (externally-managed Python — retrying with --break-system-packages)"
    $PYTHON -m pip install --user --quiet --break-system-packages requests
  fi
fi

echo "▶ Downloading ares_cli.py..."
mkdir -p "$INSTALL_DIR"
curl -fsSL "${ARES_SERVER}/api/v1/cli/ares_cli.py" -o "${INSTALL_DIR}/ares_cli.py"
chmod +x "${INSTALL_DIR}/ares_cli.py"

echo "▶ Creating 'ares' wrapper..."
cat > "${INSTALL_DIR}/ares" <<'WRAPPER'
#!/usr/bin/env bash
exec python3 "${HOME}/.local/bin/ares_cli.py" "$@"
WRAPPER
chmod +x "${INSTALL_DIR}/ares"

echo "▶ Writing default server URL..."
mkdir -p "$CONFIG_DIR"
printf '{"server":"%s"}\\n' "${ARES_SERVER}" > "${CONFIG_DIR}/config.json"

# PATH check
if ! echo "$PATH" | grep -q "$INSTALL_DIR"; then
  echo ""
  echo "  Add ${INSTALL_DIR} to your PATH:"
  echo "    echo 'export PATH=\\$HOME/.local/bin:\\$PATH' >> ~/.bashrc && source ~/.bashrc"
fi

echo ""
echo "✓ Ares CLI installed."
echo ""
echo "  Next: ares configure"
echo "  (opens your browser to log in and authorize the CLI — no API key to paste)"
""".formatted(baseUrl, baseUrl, baseUrl);
    }

    private String buildPowerShellScript(String baseUrl) {
        return """
# Ares ASM CLI — installer for Windows (PowerShell)
# Server: %s
$ErrorActionPreference = "Stop"

$AresServer = "%s"
$InstallDir = Join-Path $env:USERPROFILE ".local\\bin"
$ConfigDir  = Join-Path $env:USERPROFILE ".ares"

Write-Host "▶ Checking Python..."
try { $py = (Get-Command python).Source } catch {
  Write-Error "Python not found. Install from https://python.org"; exit 1
}
Write-Host "  Using: $py ($(& python --version 2>&1))"

Write-Host "▶ Installing 'requests' library..."
python -m pip install --user --quiet requests

Write-Host "▶ Downloading ares_cli.py..."
New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
$scriptPath = Join-Path $InstallDir "ares_cli.py"
Invoke-WebRequest "$AresServer/api/v1/cli/ares_cli.py" -OutFile $scriptPath

Write-Host "▶ Creating 'ares.cmd' wrapper..."
$wrapper = Join-Path $InstallDir "ares.cmd"
@"
@echo off
python "%USERPROFILE%\\.local\\bin\\ares_cli.py" %%*
"@ | Set-Content $wrapper -Encoding ASCII

Write-Host "▶ Writing default server URL..."
New-Item -ItemType Directory -Force -Path $ConfigDir | Out-Null
$cfg = Join-Path $ConfigDir "config.json"
('{"server":"' + $AresServer + '"}') | Set-Content $cfg -Encoding UTF8

Write-Host ""
Write-Host "✓ Ares CLI installed."
Write-Host ""
Write-Host "  Add $InstallDir to your PATH if needed (System Properties → Environment Variables)."
Write-Host ""
Write-Host "  Next: ares configure"
Write-Host "  (opens your browser to log in and authorize the CLI — no API key to paste)"
""".formatted(baseUrl, baseUrl);
    }
}
