package com.martecyber.ares.auth;

import com.martecyber.ares.auth.dto.ApiTokenDto;
import com.martecyber.ares.auth.dto.CreateApiTokenRequest;
import com.martecyber.ares.common.NotFoundException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
public class ApiTokenService {

    private final UserApiTokenRepository repo;
    private final SecureRandom random = new SecureRandom();

    public ApiTokenService(UserApiTokenRepository repo) { this.repo = repo; }

    public List<ApiTokenDto> listForUser(Long userId) {
        return repo.findByUserId(userId).stream().map(ApiTokenDto::from).toList();
    }

    public Map<String, Object> create(Long userId, CreateApiTokenRequest req) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String plainToken = "ares_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String hash = sha256Hex(plainToken);

        UserApiToken t = new UserApiToken();
        t.setUserId(userId);
        t.setName(req.name());
        t.setTokenHash(hash);
        t.setScopes(req.scopes() != null ? req.scopes() : "[]");
        t.setExpiresAt(req.expiresAt());
        t.setCreatedAt(OffsetDateTime.now());

        UserApiToken saved = repo.save(t);
        return Map.of("token", plainToken, "metadata", ApiTokenDto.from(saved));
    }

    public void delete(Long userId, Long tokenId) {
        repo.findByUserId(userId).stream()
            .filter(t -> t.getId().equals(tokenId))
            .findFirst()
            .orElseThrow(() -> NotFoundException.of("api_token", tokenId));
        repo.deleteByUserIdAndId(userId, tokenId);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
