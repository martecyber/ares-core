package com.martecyber.ares.webhooks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.integrations.CredentialEncryptionService;
import com.martecyber.ares.workflows.WorkflowRun;
import com.martecyber.ares.workflows.WorkflowRunService;
import com.martecyber.ares.workflows.WorkflowService;
import com.martecyber.ares.workflows.WorkflowTrigger;
import com.martecyber.ares.workflows.WorkflowTriggerType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Public inbound webhook receiver — see {@link WebhookSignatureService} and V147's migration
 * comment. Deliberately its own top-level controller (not folded into {@code WorkflowController})
 * since it's unauthenticated at the Spring Security layer ({@code SecurityConfig} permits this
 * one path outright) — signature verification is this controller's own job, not
 * {@code @PreAuthorize}'s.
 */
@RestController
@RequestMapping("/api/v1/webhooks/in")
public class WebhookController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebhookEndpointRepository webhookRepo;
    private final WebhookSignatureService signatureService;
    private final CredentialEncryptionService credentialEncryption;
    private final WorkflowService workflowService;
    private final WorkflowRunService runService;

    public WebhookController(WebhookEndpointRepository webhookRepo, WebhookSignatureService signatureService,
                              CredentialEncryptionService credentialEncryption, WorkflowService workflowService,
                              WorkflowRunService runService) {
        this.webhookRepo = webhookRepo;
        this.signatureService = signatureService;
        this.credentialEncryption = credentialEncryption;
        this.workflowService = workflowService;
        this.runService = runService;
    }

    @PostMapping("/{token}")
    public ResponseEntity<Map<String, Object>> receive(@PathVariable String token,
            @RequestHeader(value = "X-Webhook-Signature", required = false) String signature,
            @RequestBody(required = false) String rawBody) {
        WebhookEndpoint endpoint = webhookRepo.findByToken(token)
            .orElseThrow(() -> NotFoundException.of("webhook", token));
        if (!endpoint.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Webhook is disabled");
        }

        byte[] payload = (rawBody == null ? "" : rawBody).getBytes(StandardCharsets.UTF_8);
        String secret = credentialEncryption.decrypt(endpoint.getSecretCiphertext(), endpoint.getSecretIv());
        if (!signatureService.verify(secret, payload, signature)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or missing X-Webhook-Signature");
        }

        WorkflowTrigger trigger = workflowService.triggersFor(endpoint.getWorkflowId()).stream()
            .filter(t -> t.getNodeId().equals(endpoint.getNodeId()) && WorkflowTriggerType.WEBHOOK.equals(t.getTriggerType()))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                "Webhook's trigger node no longer exists on this workflow"));
        if (!trigger.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Trigger is disabled");
        }

        endpoint.setRequestCount(endpoint.getRequestCount() + 1);
        endpoint.setLastTriggeredAt(OffsetDateTime.now());
        webhookRepo.save(endpoint);

        Map<String, Object> triggerContext = parseBody(rawBody);
        WorkflowRun run = runService.start(endpoint.getWorkflowId(), endpoint.getNodeId(), triggerContext, "webhook", null);
        return ResponseEntity.ok(Map.of("runId", run.getId(), "status", run.getStatus()));
    }

    /** Tolerant: a non-JSON or empty body just means an empty trigger context, not a 400 — plenty
     *  of legitimate webhook senders POST an empty body or a non-JSON payload (a bare ping). */
    private Map<String, Object> parseBody(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) return Map.of();
        try {
            return MAPPER.readValue(rawBody, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of("raw", rawBody);
        }
    }
}
