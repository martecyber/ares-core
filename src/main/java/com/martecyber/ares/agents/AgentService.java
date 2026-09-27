package com.martecyber.ares.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.agents.dto.AgentDto;
import com.martecyber.ares.agents.dto.AgentRequests.*;
import com.martecyber.ares.common.NotFoundException;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class AgentService {

    private static final SecureRandom RNG = new SecureRandom();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** How often agents should heartbeat (seconds). Returned in every response. */
    public static final int HEARTBEAT_INTERVAL_SECONDS = 30;

    private final AgentRepository repo;
    private final com.martecyber.ares.agents.tasks.AgentToolSpecRegistry toolSpecRegistry;

    public AgentService(AgentRepository repo, com.martecyber.ares.agents.tasks.AgentToolSpecRegistry toolSpecRegistry) {
        this.repo = repo;
        this.toolSpecRegistry = toolSpecRegistry;
    }

    public Page<AgentDto> list(int page, int size) {
        return repo.findAllByOrderByNameAsc(
            PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200)))
            .map(AgentDto::from);
    }

    public AgentDto get(Long id) {
        return AgentDto.from(load(id));
    }

    /** Admin creates the agent row + a one-time enrollment code. Token is set later on /enroll. */
    @Transactional
    public CreateAgentResponse create(CreateAgent req, Long createdBy) {
        if (req.name() == null || req.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        Agent a = new Agent();
        a.setName(req.name().trim());
        a.setDescription(req.description());
        a.setEnrollmentCode(randomToken(24));  // 24 bytes ≈ 32 chars base64url
        a.setRegisteredAt(OffsetDateTime.now());
        a.setRegisteredByUser(createdBy);
        Agent saved = repo.save(a);
        return new CreateAgentResponse(AgentDto.from(saved), saved.getEnrollmentCode());
    }

    @Transactional
    public AgentDto update(Long id, UpdateAgent req) {
        Agent a = load(id);
        if (req.name() != null && !req.name().isBlank()) a.setName(req.name().trim());
        if (req.description() != null) a.setDescription(req.description());
        if (req.enabled() != null) a.setEnabled(req.enabled());
        if (req.maxConcurrentTasks() != null && req.maxConcurrentTasks() >= 1) {
            a.setMaxConcurrentTasks(req.maxConcurrentTasks());
        }
        return AgentDto.from(repo.save(a));
    }

    @Transactional
    public void delete(Long id) {
        if (!repo.existsById(id)) throw NotFoundException.of("agent", id);
        repo.deleteById(id);
    }

    /**
     * Agent calls this once with its enrollment code. Server mints a long-lived bearer token,
     * stores its SHA-256, captures the agent's self-reported environment + capabilities, clears
     * the enrollment code, and returns the plaintext token (the only time it appears).
     */
    @Transactional
    public EnrollResponse enroll(EnrollRequest req) {
        if (req == null || req.code() == null || req.code().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "enrollment code required");
        }
        Agent a = repo.findByEnrollmentCode(req.code().trim())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid enrollment code"));
        if (a.getTokenHash() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "agent already enrolled");
        }
        String plain = "agent_" + randomToken(32);
        a.setTokenHash(sha256Hex(plain));
        a.setEnrollmentCode(null);
        a.setHostname(req.hostname());
        a.setPlatform(req.platform());
        a.setArch(req.arch());
        a.setVersion(req.version());
        a.setCapabilities(serializeCapabilities(req.capabilities()));
        a.setLastSeenAt(OffsetDateTime.now());
        Agent saved = repo.save(a);
        return new EnrollResponse(saved.getId(), plain, HEARTBEAT_INTERVAL_SECONDS);
    }

    /** Periodic agent ping. Updates last_seen_at, capabilities, version. Returns next-step hints. */
    @Transactional
    public HeartbeatResponse heartbeat(Long agentId, HeartbeatRequest req, String latestVersion) {
        Agent a = load(agentId);
        if (!a.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "agent disabled");
        }
        if (req != null) {
            if (req.version() != null) a.setVersion(req.version());
            if (req.capabilities() != null) a.setCapabilities(serializeCapabilities(req.capabilities()));
            if (req.unavailableTools() != null) a.setUnavailableTools(serializeUnavailable(req.unavailableTools()));
            if (req.maxConcurrentTasks() != null && req.maxConcurrentTasks() >= 1)
                a.setMaxConcurrentTasks(req.maxConcurrentTasks());
        }
        a.setLastSeenAt(OffsetDateTime.now());
        repo.save(a);
        return new HeartbeatResponse(latestVersion, HEARTBEAT_INTERVAL_SECONDS, false /* phase 3 */, toolSpecRegistry.version());
    }

    private Agent load(Long id) {
        return repo.findById(id).orElseThrow(() -> NotFoundException.of("agent", id));
    }

    private static String serializeCapabilities(java.util.List<CapabilityEntry> caps) {
        if (caps == null || caps.isEmpty()) return "[]";
        try { return MAPPER.writeValueAsString(caps); } catch (Exception e) { return "[]"; }
    }

    private static String serializeUnavailable(java.util.List<UnavailableToolEntry> entries) {
        if (entries == null || entries.isEmpty()) return "[]";
        try { return MAPPER.writeValueAsString(entries); } catch (Exception e) { return "[]"; }
    }

    static String randomToken(int bytes) {
        byte[] buf = new byte[bytes];
        RNG.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
