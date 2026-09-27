package com.martecyber.ares.auth;

import com.martecyber.ares.auth.dto.LoginRequest;
import com.martecyber.ares.auth.dto.LoginResponse;
import com.martecyber.ares.auth.dto.LogoutRequest;
import com.martecyber.ares.auth.dto.RefreshRequest;
import com.martecyber.ares.auth.dto.UserDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        return auth.login(req.email(), req.password(), req.totp());
    }

    @PostMapping("/refresh")
    public LoginResponse refresh(@Valid @RequestBody RefreshRequest req) {
        return auth.refresh(req.refreshToken());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody(required = false) LogoutRequest req) {
        if (req != null) auth.logout(req.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<UserDto> me() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || a.getPrincipal() == null || "anonymousUser".equals(a.getPrincipal())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Long userId = Long.parseLong(a.getName());
        return ResponseEntity.ok(auth.loadCurrent(userId));
    }

    @ExceptionHandler(AuthService.InvalidCredentialsException.class)
    public ResponseEntity<?> handleInvalid() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(new ErrorResponse("invalid_credentials", "Email or password is incorrect, or refresh token is not valid."));
    }

    @ExceptionHandler(AuthService.MfaRequiredException.class)
    public ResponseEntity<?> handleMfaRequired() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(new ErrorResponse("mfa_required", "A TOTP code is required to complete login."));
    }

    public record ErrorResponse(String code, String message) {}
}
