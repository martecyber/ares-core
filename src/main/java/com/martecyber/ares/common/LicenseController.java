package com.martecyber.ares.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Public licensing endpoint — ares-core is the single source of truth for the project's
 * license, copyright holder and source-code locations across all four components
 * (ares-core, ares-ui, ares-agent, ares-cli); ares-ui's License page renders whatever this
 * returns rather than hardcoding its own copy (same "UI reflects, API declares" pattern as
 * {@link VersionsController}). The per-component {@code sourceUrl*} fields exist because
 * the AGPL's own network-use clause (§13) requires that anyone interacting with the
 * running app over a network can reach the corresponding source — a license notice alone
 * doesn't satisfy that without a working link to get the code.
 */
@RestController
@RequestMapping("/api/v1/license")
public class LicenseController {

    @Value("${ares.license.spdx-id:AGPL-3.0-or-later}")
    private String spdxId;

    @Value("${ares.license.name:GNU Affero General Public License v3.0 or later}")
    private String licenseName;

    @Value("${ares.license.url:https://www.gnu.org/licenses/agpl-3.0.html}")
    private String licenseUrl;

    @Value("${ares.license.copyright-holder:Martín Romera}")
    private String copyrightHolder;

    @Value("${ares.license.copyright-year:2026}")
    private int copyrightYear;

    @Value("${ares.license.source-url-core:https://github.com/martecyber/ares-core}")
    private String sourceUrlCore;

    @Value("${ares.license.source-url-ui:https://github.com/martecyber/ares-ui}")
    private String sourceUrlUi;

    @Value("${ares.license.source-url-agent:https://github.com/martecyber/ares-agent}")
    private String sourceUrlAgent;

    @Value("${ares.license.source-url-cli:https://github.com/martecyber/ares-cli}")
    private String sourceUrlCli;

    /** Read once at startup — the bundled LICENSE text never changes at runtime. */
    private final String licenseText;

    public LicenseController() throws IOException {
        try (var in = new ClassPathResource("LICENSE").getInputStream()) {
            this.licenseText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public record LicenseInfoDto(
        String spdxId, String licenseName, String licenseUrl,
        String copyrightHolder, int copyrightYear,
        String sourceUrlCore, String sourceUrlUi, String sourceUrlAgent, String sourceUrlCli
    ) {}

    @GetMapping
    public LicenseInfoDto info() {
        return new LicenseInfoDto(spdxId, licenseName, licenseUrl,
            copyrightHolder, copyrightYear, sourceUrlCore, sourceUrlUi, sourceUrlAgent, sourceUrlCli);
    }

    /** Full AGPL-3.0 text, verbatim from the bundled LICENSE file. */
    @GetMapping(value = "/text", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public String text() {
        return licenseText;
    }
}
