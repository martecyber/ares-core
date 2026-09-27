package com.martecyber.ares.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Controller for ping endpoint to check the health of the server
 */
@RestController
@RequestMapping("/api/v1")
public class PingController {

	/**
	 * GET Mapping for /ping endpoint
	 * @return a Map object telling the status, service name and current time.
	 */
    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of(
            "status", "ok",
            "service", "ares-core",
            "time", Instant.now().toString()
        );
    }
}
