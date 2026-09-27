package com.martecyber.ares.integrations.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.integrations.CredentialEncryptionService;
import com.martecyber.ares.integrations.notifications.dto.MessagingDtos.IntegrationDto;
import com.martecyber.ares.integrations.notifications.dto.MessagingDtos.TestRequest;
import com.martecyber.ares.integrations.notifications.dto.MessagingDtos.UpdateIntegrationRequest;
import com.martecyber.ares.integrations.notifications.senders.MessagingSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Covers two real bugs found while wiring up email integration editing: (1) update() used to
 *  blindly REPLACE the encrypted config with whatever the request carried, so any partial edit
 *  (rename, toggle enabled, change one field) silently wiped every other stored field — including
 *  the password, since the edit dialog never resubmits secrets it doesn't have; (2) the API never
 *  echoed back any config at all, so the edit dialog had nothing to prefill and every field looked
 *  blank even when fully configured. Uses a real {@link CredentialEncryptionService} (not mocked)
 *  so the encrypt/decrypt/merge round trip is genuinely exercised. */
class MessagingServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MessagingIntegrationRepository integrations;
    private CredentialEncryptionService crypto;
    private MessagingService service;

    @BeforeEach
    void setUp() {
        integrations = mock(MessagingIntegrationRepository.class);
        crypto = new CredentialEncryptionService(Base64.getEncoder().encodeToString(new byte[32]));
        when(integrations.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new MessagingService(integrations,
            mock(MessagingIntegrationGrantRepository.class), crypto, List.of());
    }

    private MessagingIntegration integration(Long id, String kind, Map<String, Object> config) throws Exception {
        MessagingIntegration i = new MessagingIntegration();
        i.setName(kind);
        i.setKind(kind);
        i.setEnabled(true);
        var enc = crypto.encrypt(MAPPER.writeValueAsString(config));
        i.setConfigCiphertext(enc.ciphertext());
        i.setConfigIv(enc.iv());
        when(integrations.findById(id)).thenReturn(Optional.of(i));
        return i;
    }

    @Test
    void updateMergesOntoExistingConfigInsteadOfReplacingIt() throws Exception {
        MessagingIntegration i = integration(1L, "email", Map.of(
            "host", "smtp.old.com", "port", 587, "username", "u", "password", "secret", "fromAddress", "a@x.com", "useTls", true));

        // Edit dialog only resubmits the one field the operator actually changed.
        service.update(1L, new UpdateIntegrationRequest(null, null, Map.of("host", "smtp.new.com")));

        String json = crypto.decrypt(i.getConfigCiphertext(), i.getConfigIv());
        assertTrue(json.contains("smtp.new.com"));
        assertTrue(json.contains("secret"), "password must survive a partial update — was: " + json);
        assertTrue(json.contains("\"port\":587"), "port must survive a partial update — was: " + json);
    }

    @Test
    void getExposesNonSecretEmailConfigButNeverThePassword() throws Exception {
        integration(1L, "email", Map.of(
            "host", "smtp.example.com", "port", 587, "username", "u", "password", "secret", "fromAddress", "a@x.com", "useTls", true));

        IntegrationDto dto = service.get(1L);

        assertNotNull(dto.emailConfig());
        assertEquals("smtp.example.com", dto.emailConfig().host());
        assertEquals(587, dto.emailConfig().port());
        assertEquals("a@x.com", dto.emailConfig().fromAddress());
        assertEquals(Boolean.TRUE, dto.emailConfig().useTls());
        // No password field on the record at all — the DTO type itself can't leak it.
    }

    @Test
    void nonEmailIntegrationHasNullEmailConfig() throws Exception {
        integration(2L, "slack", Map.of("webhookUrl", "https://x"));

        assertNull(service.get(2L).emailConfig());
    }

    @Test
    void sendTestPassesRecipientsThroughToTheSender() throws Exception {
        MessagingSender emailSender = mock(MessagingSender.class);
        when(emailSender.kind()).thenReturn(MessagingKind.EMAIL);
        MessagingService svc = new MessagingService(integrations,
            mock(MessagingIntegrationGrantRepository.class), crypto, List.of(emailSender));

        integration(1L, "email", Map.of("host", "h", "port", 25, "fromAddress", "a@x.com"));

        svc.sendTest(1L, new TestRequest("Subj", "Body", "info", List.of("dest@example.com"), null, null));

        var captor = org.mockito.ArgumentCaptor.forClass(NotificationMessage.class);
        verify(emailSender).send(any(), captor.capture());
        assertEquals(List.of("dest@example.com"), captor.getValue().to());
    }
}
