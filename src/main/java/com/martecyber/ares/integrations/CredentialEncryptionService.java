package com.martecyber.ares.integrations;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption/decryption for integration credentials.
 * The master key is injected from application config / env var ARES_INTEGRATION_CREDENTIAL_KEY.
 */
@Service
public class CredentialEncryptionService {

    private static final String ALGORITHM  = "AES/GCM/NoPadding";
    private static final int    GCM_IV_LEN = 12; // bytes (96-bit IV recommended for GCM)
    private static final int    GCM_TAG_LEN = 128; // bits

    private final SecretKey key;
    private final SecureRandom rng = new SecureRandom();

    public CredentialEncryptionService(
            @Value("${ares.integrations.credential-key}") String base64Key) {
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    public record Encrypted(byte[] ciphertext, byte[] iv) {}

    public Encrypted encrypt(String plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_LEN];
            rng.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LEN, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new Encrypted(ciphertext, iv);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt credentials", e);
        }
    }

    public String decrypt(byte[] ciphertext, byte[] iv) {
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LEN, iv));
            byte[] plain = cipher.doFinal(ciphertext);
            return new String(plain, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt credentials", e);
        }
    }
}
