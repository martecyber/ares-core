package com.martecyber.ares.auth;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

@Service
public class TotpService {

    private static final int DIGITS = 6;
    private static final int STEP_SECONDS = 30;
    private static final int WINDOW = 1; // accept ±1 window for clock skew

    public boolean verify(byte[] secret, String code) {
        if (code == null || code.isBlank()) return false;
        String stripped = code.replaceAll("\\s", "");
        if (stripped.length() != DIGITS || !stripped.chars().allMatch(Character::isDigit)) return false;
        long expected = Long.parseLong(stripped);
        long counter = Instant.now().getEpochSecond() / STEP_SECONDS;
        for (int i = -WINDOW; i <= WINDOW; i++) {
            if (hotp(secret, counter + i) == expected) return true;
        }
        return false;
    }

    private static long hotp(byte[] secret, long counter) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "RAW"));
            byte[] msg = ByteBuffer.allocate(8).putLong(counter).array();
            byte[] hash = mac.doFinal(msg);
            int offset = hash[hash.length - 1] & 0x0f;
            long code = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
            long mod = 1;
            for (int i = 0; i < DIGITS; i++) mod *= 10;
            return code % mod;
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
