package com.martecyber.ares.cli;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * The authenticated half of the CLI device-login flow: the "Authorize CLI" page in ares-ui
 * calls these once the user is logged in. Deliberately NOT under /api/v1/cli/** (which is
 * permitAll) so it falls under SecurityConfig's default anyRequest().authenticated() rule.
 */
@RestController
@RequestMapping("/api/v1/users/me/cli-device-auth")
public class CliDeviceApprovalController {

    private final CliDeviceAuthService svc;

    public CliDeviceApprovalController(CliDeviceAuthService svc) { this.svc = svc; }

    public record CodeRequest(String code) {}

    @PostMapping("/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(Authentication auth, @RequestBody CodeRequest req) {
        svc.approve(req.code(), Long.parseLong(auth.getName()));
    }

    @PostMapping("/deny")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deny(Authentication auth, @RequestBody CodeRequest req) {
        svc.deny(req.code());
    }
}
