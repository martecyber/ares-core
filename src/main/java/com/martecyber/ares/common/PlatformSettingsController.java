package com.martecyber.ares.common;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/settings")
@PreAuthorize("hasRole('MSSP_ADMIN')")
public class PlatformSettingsController {

    private final PlatformSettingsService service;

    public PlatformSettingsController(PlatformSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, String> get() {
        return Map.of("timezone", service.getTimezone());
    }

    @PatchMapping
    public Map<String, String> patch(@RequestBody Map<String, String> body) {
        if (body.containsKey("timezone")) {
            service.setTimezone(body.get("timezone"));
        }
        return Map.of("timezone", service.getTimezone());
    }
}
