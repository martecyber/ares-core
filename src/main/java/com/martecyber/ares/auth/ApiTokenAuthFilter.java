package com.martecyber.ares.auth;

import com.martecyber.ares.users.UserRoleRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;

/**
 * Authenticates requests carrying an API token in the X-API-Key header.
 * The token must be prefixed with "ares_" and its SHA-256 hash must match a
 * non-expired row in user_api_token.
 * Runs before JwtAuthenticationFilter; if the SecurityContext is already
 * populated by a previous filter this one is a no-op.
 */
@Component
public class ApiTokenAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiTokenAuthFilter.class);
    private static final String HEADER = "X-API-Key";

    private final UserApiTokenRepository tokenRepo;
    private final UserRoleRepository roleRepo;

    public ApiTokenAuthFilter(UserApiTokenRepository tokenRepo, UserRoleRepository roleRepo) {
        this.tokenRepo = tokenRepo;
        this.roleRepo  = roleRepo;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        // Skip if already authenticated (e.g. valid JWT was processed first)
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            chain.doFilter(req, res);
            return;
        }

        String raw = req.getHeader(HEADER);
        if (raw == null || !raw.startsWith("ares_")) {
            chain.doFilter(req, res);
            return;
        }

        String hash = sha256Hex(raw);
        tokenRepo.findByTokenHash(hash).ifPresent(token -> {
            if (token.getExpiresAt() != null && token.getExpiresAt().isBefore(OffsetDateTime.now())) {
                log.debug("API token {} is expired", token.getId());
                return;
            }
            // Update last_used_at asynchronously — best-effort, non-blocking
            token.setLastUsedAt(OffsetDateTime.now());
            tokenRepo.save(token);

            var userRoles = roleRepo.findByUserId(token.getUserId()).stream()
                .map(com.martecyber.ares.users.UserRole::getRole)
                .filter(java.util.Objects::nonNull)
                .toList();
            var roleAuthorities = userRoles.stream()
                .map(r -> r.getCode())
                .distinct()
                .map(code -> new SimpleGrantedAuthority("ROLE_" + code));
            // Fine-grained permission codes (e.g. "SSVC_WRITE"), unprefixed — mirrors
            // JwtAuthenticationFilter so hasAuthority(...) checks work the same way
            // regardless of whether the request used a session JWT or an API token.
            var permissionAuthorities = userRoles.stream()
                .flatMap(r -> r.getPermissions().stream())
                .map(p -> p.getCode())
                .distinct()
                .map(SimpleGrantedAuthority::new);
            var authorities = java.util.stream.Stream.concat(roleAuthorities, permissionAuthorities).toList();

            var auth = new UsernamePasswordAuthenticationToken(
                String.valueOf(token.getUserId()), null, authorities);
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(req));
            SecurityContextHolder.getContext().setAuthentication(auth);
        });

        chain.doFilter(req, res);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
