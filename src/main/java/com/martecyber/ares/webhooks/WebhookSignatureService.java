package com.martecyber.ares.webhooks;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * HMAC-SHA256 signing/verification for inbound and outbound webhooks (Workflows implementation
 * plan, Phase C — built as a genuinely general-purpose primitive, reused as-is by Phase F's
 * outbound {@code ACTION_WEBHOOK_CALL}). Same JDK {@code javax.crypto.Mac} style as the existing
 * HOTP precedent ({@code auth/TotpService}), plus a constant-time comparator ({@link
 * MessageDigest#isEqual}) — signature comparison must not leak timing information about how many
 * leading bytes matched.
 */
@Service
public class WebhookSignatureService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String HEADER_PREFIX = "sha256=";
    private final SecureRandom rng = new SecureRandom();

    /** {@code sha256=<hex-digest>} — the header value an inbound caller must send, and what
     *  an outbound {@code ACTION_WEBHOOK_CALL} sends when the target opts into signing. */
    public String sign(String secret, byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(payload);
            return HEADER_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to compute webhook signature", e);
        }
    }

    /** Recomputes the expected signature over {@code payload} and compares it against the
     *  caller-supplied header value in constant time. Tolerates a missing/malformed header
     *  (returns false rather than throwing) since that's just "signature check failed," not an
     *  exceptional condition. */
    public boolean verify(String secret, byte[] payload, String suppliedHeader) {
        if (suppliedHeader == null || suppliedHeader.isBlank()) return false;
        String expected = sign(secret, payload);
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = suppliedHeader.trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    /** Random URL-safe token for the public {@code /webhooks/in/{token}} path — 32 bytes of
     *  entropy, base64url-encoded (no padding) so it's directly usable in a URL path segment. */
    public String generateToken() {
        return randomUrlSafe(32);
    }

    /** Random HMAC signing secret shown once (well, re-viewable — see {@link WebhookEndpoint}'s
     *  class doc) to the admin configuring the external caller. */
    public String generateSecret() {
        return randomUrlSafe(32);
    }

    private String randomUrlSafe(int bytes) {
        byte[] raw = new byte[bytes];
        rng.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
