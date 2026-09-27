package com.martecyber.ares.webhooks;

import com.martecyber.ares.integrations.CredentialEncryptionService;
import com.martecyber.ares.workflows.WorkflowRun;
import com.martecyber.ares.workflows.WorkflowRunService;
import com.martecyber.ares.workflows.WorkflowService;
import com.martecyber.ares.workflows.WorkflowTrigger;
import com.martecyber.ares.workflows.WorkflowTriggerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Pure Mockito unit test — the receiver's own logic (signature check, trigger-existence check,
 *  request counting) is independent of real persistence; DB-backed provisioning/cleanup behavior
 *  is covered separately by {@link com.martecyber.ares.workflows.WorkflowServiceWebhookIT}. */
class WebhookControllerTest {

    private static final String SECRET = "test-secret";
    private static final String CREDENTIAL_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    private WebhookEndpointRepository webhookRepo;
    private WorkflowService workflowService;
    private WorkflowRunService runService;
    private WebhookController controller;
    private CredentialEncryptionService encryption;
    private WebhookSignatureService signatureService;
    private WebhookEndpoint endpoint;

    @BeforeEach
    void setUp() {
        webhookRepo = mock(WebhookEndpointRepository.class);
        workflowService = mock(WorkflowService.class);
        runService = mock(WorkflowRunService.class);
        encryption = new CredentialEncryptionService(CREDENTIAL_KEY);
        signatureService = new WebhookSignatureService();
        controller = new WebhookController(webhookRepo, signatureService, encryption, workflowService, runService);

        var enc = encryption.encrypt(SECRET);
        endpoint = new WebhookEndpoint();
        endpoint.setToken("tok123");
        endpoint.setWorkflowId(9L);
        endpoint.setNodeId("wh1");
        endpoint.setSecretCiphertext(enc.ciphertext());
        endpoint.setSecretIv(enc.iv());
        endpoint.setEnabled(true);
        endpoint.setRequestCount(0);
        when(webhookRepo.findByToken("tok123")).thenReturn(Optional.of(endpoint));

        WorkflowTrigger trigger = new WorkflowTrigger();
        trigger.setNodeId("wh1");
        trigger.setTriggerType(WorkflowTriggerType.WEBHOOK);
        trigger.setEnabled(true);
        when(workflowService.triggersFor(9L)).thenReturn(List.of(trigger));

        WorkflowRun run = mock(WorkflowRun.class);
        when(run.getId()).thenReturn(42L);
        when(run.getStatus()).thenReturn("running");
        when(runService.start(eq(9L), eq("wh1"), any(), eq("webhook"), eq(null))).thenReturn(run);
    }

    private String sig(String body) {
        return signatureService.sign(SECRET, body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void validSignatureStartsARunAndCountsTheRequest() {
        String body = "{\"event\":\"push\"}";
        var response = controller.receive("tok123", sig(body), body);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(42L, response.getBody().get("runId"));
        verify(runService).start(eq(9L), eq("wh1"), eq(java.util.Map.of("event", "push")), eq("webhook"), eq(null));
        assertEquals(1, endpoint.getRequestCount());
        assertNotNull(endpoint.getLastTriggeredAt());
        verify(webhookRepo).save(endpoint);
    }

    @Test
    void tamperedSignatureIsRejected() {
        String body = "{\"event\":\"push\"}";
        String validSig = sig(body);
        var ex = assertThrows(ResponseStatusException.class,
            () -> controller.receive("tok123", validSig, "{\"event\":\"tampered\"}"));
        assertEquals(401, ex.getStatusCode().value());
        verify(runService, never()).start(any(), any(), any(), any(), any());
    }

    @Test
    void missingSignatureIsRejected() {
        var ex = assertThrows(ResponseStatusException.class,
            () -> controller.receive("tok123", null, "{}"));
        assertEquals(401, ex.getStatusCode().value());
    }

    @Test
    void unknownTokenIs404() {
        when(webhookRepo.findByToken("ghost")).thenReturn(Optional.empty());
        assertThrows(com.martecyber.ares.common.NotFoundException.class,
            () -> controller.receive("ghost", "sha256=x", "{}"));
    }

    @Test
    void disabledEndpointIsRejected() {
        endpoint.setEnabled(false);
        String body = "{}";
        var ex = assertThrows(ResponseStatusException.class,
            () -> controller.receive("tok123", sig(body), body));
        assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    void disabledTriggerIsRejectedEvenWithAValidSignature() {
        WorkflowTrigger disabledTrigger = new WorkflowTrigger();
        disabledTrigger.setNodeId("wh1");
        disabledTrigger.setTriggerType(WorkflowTriggerType.WEBHOOK);
        disabledTrigger.setEnabled(false);
        when(workflowService.triggersFor(9L)).thenReturn(List.of(disabledTrigger));

        String body = "{}";
        var ex = assertThrows(ResponseStatusException.class,
            () -> controller.receive("tok123", sig(body), body));
        assertEquals(409, ex.getStatusCode().value());
    }

    @Test
    void emptyBodyResolvesToAnEmptyTriggerContext() {
        controller.receive("tok123", sig(""), null);
        verify(runService).start(eq(9L), eq("wh1"), eq(java.util.Map.of()), eq("webhook"), eq(null));
    }

    @Test
    void nonJsonBodyFallsBackToARawWrapper() {
        String body = "not json at all";
        controller.receive("tok123", sig(body), body);
        verify(runService).start(eq(9L), eq("wh1"), eq(java.util.Map.of("raw", body)), eq("webhook"), eq(null));
    }
}
