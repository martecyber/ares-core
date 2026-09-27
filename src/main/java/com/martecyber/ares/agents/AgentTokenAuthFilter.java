package com.martecyber.ares.agents;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * Authenticates agent-originated requests carrying X-Agent-Token. Activates only on
 * /api/v1/agent/** paths so it never conflicts with user JWT / API-key auth. The
 * principal is the string "agent:{id}" and the authority is ROLE_AGENT.
 */
@Component
public class AgentTokenAuthFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Agent-Token";

    private final AgentRepository repo;

    public AgentTokenAuthFilter(AgentRepository repo) {
        this.repo = repo;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        // Applies to agent-facing endpoints and KB wordlist downloads (agents need to fetch wordlists).
        String path = req.getRequestURI();
        boolean isAgentPath = path.startsWith("/api/v1/agent/")
            || (path.startsWith("/api/v1/kb/wordlists/") && path.endsWith("/content"));
        if (!isAgentPath) {
            chain.doFilter(req, res);
            return;
        }
        // If something earlier already authenticated, leave it alone.
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            chain.doFilter(req, res);
            return;
        }

        String raw = req.getHeader(HEADER);
        if (raw == null || raw.isBlank()) {
            chain.doFilter(req, res);
            return;
        }

        repo.findByTokenHash(sha256Hex(raw)).ifPresent(agent -> {
            if (!agent.isEnabled()) return;
            var auth = new UsernamePasswordAuthenticationToken(
                "agent:" + agent.getId(),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_AGENT"))
            );
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(req));
            SecurityContextHolder.getContext().setAuthentication(auth);
        });

        chain.doFilter(req, res);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Helper for controllers: extract agent id from the auth principal. */
    public static Long currentAgentId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        String name = auth.getName();
        if (name == null || !name.startsWith("agent:")) return null;
        try { return Long.parseLong(name.substring(6)); } catch (NumberFormatException e) { return null; }
    }
}
