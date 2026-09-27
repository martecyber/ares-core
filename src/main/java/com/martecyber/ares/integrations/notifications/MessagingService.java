package com.martecyber.ares.integrations.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.integrations.CredentialEncryptionService;
import com.martecyber.ares.integrations.notifications.dto.MessagingDtos.*;
import com.martecyber.ares.integrations.notifications.senders.MessagingSender;

import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin CRUD for messaging integrations (Discord/Slack/Teams/Telegram/webhook/email channels) +
 * generic send. Workflows' {@code ACTION_NOTIFICATION} node is the runtime consumer that turns a
 * workflow run into a call into this service's send method.
 */
@Service
public class MessagingService {

    private static final Logger log = LoggerFactory.getLogger(MessagingService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MessagingIntegrationRepository integrations;
    private final MessagingIntegrationGrantRepository grants;
    private final CredentialEncryptionService crypto;
    private final Map<MessagingKind, MessagingSender> senders;

    public MessagingService(MessagingIntegrationRepository integrations,
                            MessagingIntegrationGrantRepository grants,
                            CredentialEncryptionService crypto,
                            List<MessagingSender> senderBeans) {
        this.integrations = integrations;
        this.grants = grants;
        this.crypto = crypto;
        this.senders = new HashMap<>();
        for (MessagingSender s : senderBeans) this.senders.put(s.kind(), s);
    }

    // ── Integration CRUD ──────────────────────────────────────────

    public List<IntegrationDto> list() {
        return integrations.findAllByOrderByNameAsc().stream().map(this::toDto).toList();
    }

    public IntegrationDto get(Long id) {
        return toDto(load(id));
    }

    @Transactional
    public IntegrationDto create(CreateIntegrationRequest req) {
        if (req == null || req.name() == null || req.name().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        MessagingKind kind = MessagingKind.parse(req.kind());
        var enc = encryptConfig(req.config());

        MessagingIntegration i = new MessagingIntegration();
        i.setName(req.name().trim());
        i.setKind(kind.code());
        i.setEnabled(true);
        i.setConfigCiphertext(enc.ciphertext());
        i.setConfigIv(enc.iv());
        return toDto(integrations.save(i));
    }

    @Transactional
    public IntegrationDto update(Long id, UpdateIntegrationRequest req) {
        MessagingIntegration i = load(id);
        if (req.name() != null && !req.name().isBlank()) i.setName(req.name().trim());
        if (req.enabled() != null) i.setEnabled(req.enabled());
        if (req.config() != null && !req.config().isEmpty()) {
            // Merge onto the existing config rather than replacing it outright — the edit dialog
            // only submits the fields the operator actually filled in (blank = "leave unchanged"),
            // and a blind replace would silently null out every field the operator didn't retype
            // (a real bug: editing just the host on a 5-field email config used to wipe the
            // password too). A field genuinely being cleared can't be expressed this way, but
            // nothing in this app's UI does that today — every kind's dialog only ever adds/updates.
            Map<String, Object> merged = new HashMap<>();
            try { merged.putAll(decryptConfig(i)); } catch (Exception ignored) { /* corrupt/never set — start fresh */ }
            merged.putAll(req.config());
            var enc = encryptConfig(merged);
            i.setConfigCiphertext(enc.ciphertext());
            i.setConfigIv(enc.iv());
        }
        i.setUpdatedAt(OffsetDateTime.now());
        return toDto(integrations.save(i));
    }

    /** Attaches the email-kind non-secret config preview (see {@code IntegrationDto}'s doc
     *  comment) — a no-op for every other kind. Decrypt failures degrade to a null preview rather
     *  than breaking the list/detail endpoint. */
    private IntegrationDto toDto(MessagingIntegration i) {
        if (!"email".equals(i.getKind())) return IntegrationDto.from(i);
        try {
            Map<String, Object> cfg = decryptConfig(i);
            Object port = cfg.get("port");
            var emailConfig = new com.martecyber.ares.integrations.notifications.dto.MessagingDtos.EmailConfigDto(
                (String) cfg.get("host"),
                port instanceof Number n ? n.intValue() : null,
                (String) cfg.get("username"),
                (String) cfg.get("fromAddress"),
                Boolean.TRUE.equals(cfg.get("useTls")));
            return IntegrationDto.from(i, emailConfig);
        } catch (Exception e) {
            return IntegrationDto.from(i);
        }
    }

    @Transactional
    public void delete(Long id) {
        if (!integrations.existsById(id)) throw NotFoundException.of("messaging integration", id);
        integrations.deleteById(id);
    }

    // ── Sending ───────────────────────────────────────────────────

    /**
     * Sends a single message through one integration. Failures are logged and swallowed
     * (no exception propagated) — the dispatcher fan-out shouldn't be aborted by one
     * unreachable webhook. For the admin "Test" endpoint we re-throw so the operator
     * sees the error in the UI.
     */
    public void send(MessagingIntegration i, NotificationMessage msg, boolean rethrow) {
        if (!i.isEnabled()) return;
        MessagingKind kind;
        try { kind = MessagingKind.parse(i.getKind()); }
        catch (Exception e) {
            log.warn("Integration {} has unknown kind '{}'", i.getId(), i.getKind());
            return;
        }
        MessagingSender sender = senders.get(kind);
        if (sender == null) {
            log.warn("No sender bean registered for kind {}", kind);
            return;
        }
        try {
            Map<String, Object> cfg = decryptConfig(i);
            sender.send(cfg, msg);
        } catch (Exception e) {
            log.warn("Messaging delivery failed for integration {} ({}): {}",
                i.getId(), i.getKind(), e.getMessage());
            if (rethrow) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Delivery failed: " + e.getMessage(), e);
        }
    }

    /** Admin "Test" endpoint — sends an ad-hoc message and surfaces transport errors.
     *  {@code to}/{@code cc}/{@code bcc} matter only for email-kind integrations — the "Send
     *  test" dialog prompts for a destination address before calling this for those, since
     *  (unlike every other transport) an email integration has no built-in destination. */
    public void sendTest(Long integrationId, TestRequest req) {
        MessagingIntegration i = load(integrationId);
        NotificationMessage msg = new NotificationMessage(
            req != null && req.title() != null ? req.title() : "Ares test message",
            req != null && req.body()  != null ? req.body()  : "If you see this, the integration works.",
            req != null && req.severity() != null ? req.severity() : "info",
            null,
            false,
            req != null && req.to()  != null ? req.to()  : List.of(),
            req != null && req.cc()  != null ? req.cc()  : List.of(),
            req != null && req.bcc() != null ? req.bcc() : List.of()
        );
        send(i, msg, true);
    }

    // ── Grants ────────────────────────────────────────────────────

    public List<GrantDto> listGrants(Long integrationId) {
        load(integrationId);
        return grants.findByIntegrationIdOrderByCreatedAtAsc(integrationId).stream()
            .map(GrantDto::from).toList();
    }

    @Transactional
    public GrantDto createGrant(Long integrationId, CreateGrantRequest req) {
        load(integrationId); // 404 if absent
        if (req == null || req.organizationId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "organizationId is required");
        }
        MessagingIntegrationGrant g = new MessagingIntegrationGrant();
        g.setIntegrationId(integrationId);
        g.setOrganizationId(req.organizationId());
        g.setProjectId(req.projectId());
        try {
            return GrantDto.from(grants.save(g));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Partial unique index hit — that combination is already granted.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Integration is already granted to that " + (req.projectId() == null ? "organization" : "project"));
        }
    }

    @Transactional
    public void revokeGrant(Long grantId) {
        if (!grants.existsById(grantId)) throw NotFoundException.of("messaging grant", grantId);
        grants.deleteById(grantId);
    }

    /** True if any active grant authorizes this integration for the given (org, project). */
    public boolean isGrantedToProject(Long integrationId, Long organizationId, Long projectId) {
        return grants.findForProject(organizationId, projectId).stream()
            .anyMatch(g -> g.getIntegrationId().equals(integrationId));
    }

    /** True if any active grant authorizes this integration anywhere in the org. */
    public boolean isGrantedToOrg(Long integrationId, Long organizationId) {
        return grants.findForOrganization(organizationId).stream()
            .anyMatch(g -> g.getIntegrationId().equals(integrationId));
    }

    /** Project-visible integrations: enabled + has an active grant covering this scope. */
    public List<IntegrationDto> listForProject(Long organizationId, Long projectId) {
        var integ = grants.findForProject(organizationId, projectId).stream()
            .map(MessagingIntegrationGrant::getIntegrationId)
            .distinct().toList();
        if (integ.isEmpty()) return List.of();
        return integrations.findAllById(integ).stream()
            .filter(MessagingIntegration::isEnabled)
            .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
            .map(IntegrationDto::from).toList();
    }

    /** Org-visible integrations: enabled + has any grant in this org (project-specific or org-wide). */
    public List<IntegrationDto> listForOrganization(Long organizationId) {
        var integ = grants.findForOrganization(organizationId).stream()
            .map(MessagingIntegrationGrant::getIntegrationId)
            .distinct().toList();
        if (integ.isEmpty()) return List.of();
        return integrations.findAllById(integ).stream()
            .filter(MessagingIntegration::isEnabled)
            .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
            .map(IntegrationDto::from).toList();
    }

    // ── Internals ─────────────────────────────────────────────────

    private MessagingIntegration load(Long id) {
        return integrations.findById(id)
            .orElseThrow(() -> NotFoundException.of("messaging integration", id));
    }

    private CredentialEncryptionService.Encrypted encryptConfig(Map<String, Object> config) {
        try {
            String json = MAPPER.writeValueAsString(config == null ? Map.of() : config);
            return crypto.encrypt(json);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid config payload");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> decryptConfig(MessagingIntegration i) {
        String json = crypto.decrypt(i.getConfigCiphertext(), i.getConfigIv());
        try { return MAPPER.readValue(json, Map.class); }
        catch (Exception e) { throw new RuntimeException("Corrupted integration config for " + i.getId()); }
    }
}
