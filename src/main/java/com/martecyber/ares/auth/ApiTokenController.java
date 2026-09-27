package com.martecyber.ares.auth;

import com.martecyber.ares.auth.dto.ApiTokenDto;
import com.martecyber.ares.auth.dto.CreateApiTokenRequest;
import io.jsonwebtoken.Claims;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users/me/api-tokens")
public class ApiTokenController {

    private final ApiTokenService svc;

    public ApiTokenController(ApiTokenService svc) { this.svc = svc; }

    @GetMapping
    public List<ApiTokenDto> list(Authentication auth) {
        return svc.listForUser(resolveUserId(auth));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(Authentication auth, @Valid @RequestBody CreateApiTokenRequest req) {
        return svc.create(resolveUserId(auth), req);
    }

    @DeleteMapping("/{tokenId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication auth, @PathVariable Long tokenId) {
        svc.delete(resolveUserId(auth), tokenId);
    }

    private static Long resolveUserId(Authentication auth) {
        return Long.parseLong(auth.getName());
    }
}
