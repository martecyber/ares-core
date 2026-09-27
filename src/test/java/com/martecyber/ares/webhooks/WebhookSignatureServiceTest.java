package com.martecyber.ares.webhooks;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class WebhookSignatureServiceTest {

    private final WebhookSignatureService service = new WebhookSignatureService();

    @Test
    void signProducesSha256PrefixedHexDigest() {
        String sig = service.sign("s3cr3t", "hello".getBytes(StandardCharsets.UTF_8));
        assertTrue(sig.startsWith("sha256="));
        assertEquals(71, sig.length()); // "sha256=" (7) + 64 hex chars
    }

    @Test
    void signIsDeterministicForTheSamePayloadAndSecret() {
        byte[] payload = "{\"event\":\"ping\"}".getBytes(StandardCharsets.UTF_8);
        assertEquals(service.sign("secret", payload), service.sign("secret", payload));
    }

    @Test
    void differentSecretsProduceDifferentSignatures() {
        byte[] payload = "same body".getBytes(StandardCharsets.UTF_8);
        assertNotEquals(service.sign("secretA", payload), service.sign("secretB", payload));
    }

    @Test
    void verifyAcceptsACorrectlySignedPayload() {
        byte[] payload = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        String sig = service.sign("my-secret", payload);
        assertTrue(service.verify("my-secret", payload, sig));
    }

    @Test
    void verifyRejectsATamperedPayload() {
        byte[] original = "{\"amount\":10}".getBytes(StandardCharsets.UTF_8);
        String sig = service.sign("my-secret", original);
        byte[] tampered = "{\"amount\":9999}".getBytes(StandardCharsets.UTF_8);
        assertFalse(service.verify("my-secret", tampered, sig));
    }

    @Test
    void verifyRejectsTheWrongSecret() {
        byte[] payload = "body".getBytes(StandardCharsets.UTF_8);
        String sig = service.sign("correct-secret", payload);
        assertFalse(service.verify("wrong-secret", payload, sig));
    }

    @Test
    void verifyRejectsMissingOrBlankHeader() {
        byte[] payload = "body".getBytes(StandardCharsets.UTF_8);
        assertFalse(service.verify("secret", payload, null));
        assertFalse(service.verify("secret", payload, ""));
        assertFalse(service.verify("secret", payload, "   "));
    }

    @Test
    void verifyRejectsAMalformedSignatureValue() {
        byte[] payload = "body".getBytes(StandardCharsets.UTF_8);
        assertFalse(service.verify("secret", payload, "not-a-real-signature"));
    }

    @Test
    void generateTokenAndSecretAreNonEmptyAndDistinctAcrossCalls() {
        String t1 = service.generateToken();
        String t2 = service.generateToken();
        assertNotNull(t1);
        assertFalse(t1.isBlank());
        assertNotEquals(t1, t2);

        String s1 = service.generateSecret();
        String s2 = service.generateSecret();
        assertNotEquals(s1, s2);
        assertNotEquals(t1, s1);
    }
}
