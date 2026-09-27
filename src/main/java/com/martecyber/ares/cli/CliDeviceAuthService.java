package com.martecyber.ares.cli;

import com.martecyber.ares.auth.ApiTokenService;
import com.martecyber.ares.auth.dto.CreateApiTokenRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;

/**
 * Backs `ares configure`'s browser login: the CLI starts a device request, opens the
 * verification URL in the user's browser, and polls until the user approves it there
 * (mirrors the device-authorization flow used by tools like `gh`/`heroku` CLIs — no API key
 * ever needs to be typed or pasted).
 */
@Service
public class CliDeviceAuthService {

    private static final int EXPIRY_MINUTES = 10;
    private static final String USER_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O/1/I

    private final CliDeviceAuthRepository repo;
    private final ApiTokenService apiTokenService;
    private final SecureRandom random = new SecureRandom();

    public CliDeviceAuthService(CliDeviceAuthRepository repo, ApiTokenService apiTokenService) {
        this.repo = repo;
        this.apiTokenService = apiTokenService;
    }

    public CliDeviceAuth start(String clientInfo) {
        repo.deleteByExpiresAtBefore(OffsetDateTime.now().minusDays(1));

        CliDeviceAuth a = new CliDeviceAuth();
        a.setDeviceCode("cdev_" + randomUrlSafe(32));
        a.setUserCode(randomUserCode());
        a.setClientInfo(clientInfo);
        a.setStatus(CliDeviceAuth.PENDING);
        a.setCreatedAt(OffsetDateTime.now());
        a.setExpiresAt(OffsetDateTime.now().plusMinutes(EXPIRY_MINUTES));
        return repo.save(a);
    }

    public CliDeviceAuth info(String deviceCode) {
        CliDeviceAuth a = load(deviceCode);
        expireIfDue(a);
        return a;
    }

    /** Called by the CLI in a loop. Approved is a one-time read: the plaintext token is
     *  handed back exactly once, then cleared, and the row is marked CONSUMED. */
    public Map<String, Object> poll(String deviceCode) {
        CliDeviceAuth a = load(deviceCode);
        expireIfDue(a);

        return switch (a.getStatus()) {
            case CliDeviceAuth.PENDING -> Map.of("status", "pending");
            case CliDeviceAuth.DENIED -> Map.of("status", "denied");
            case CliDeviceAuth.APPROVED -> {
                String token = a.getApiToken();
                a.setApiToken(null);
                a.setStatus(CliDeviceAuth.CONSUMED);
                repo.save(a);
                yield Map.of("status", "approved", "apiKey", token);
            }
            default -> Map.of("status", "expired"); // EXPIRED or already-CONSUMED
        };
    }

    /** Called from the authenticated "Authorize CLI" page. Mints a real user_api_token and
     *  stashes its plaintext for the CLI's next poll to pick up. */
    public void approve(String deviceCode, Long userId) {
        CliDeviceAuth a = load(deviceCode);
        expireIfDue(a);
        if (!CliDeviceAuth.PENDING.equals(a.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This authorization request is no longer pending.");
        }
        String label = "CLI" + (a.getClientInfo() != null && !a.getClientInfo().isBlank() ? " - " + a.getClientInfo() : "");
        Map<String, Object> created = apiTokenService.create(userId, new CreateApiTokenRequest(truncate(label, 50), null, null));
        a.setApiToken((String) created.get("token"));
        a.setUserId(userId);
        a.setStatus(CliDeviceAuth.APPROVED);
        repo.save(a);
    }

    public void deny(String deviceCode) {
        CliDeviceAuth a = load(deviceCode);
        expireIfDue(a);
        if (!CliDeviceAuth.PENDING.equals(a.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This authorization request is no longer pending.");
        }
        a.setStatus(CliDeviceAuth.DENIED);
        repo.save(a);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private CliDeviceAuth load(String deviceCode) {
        return repo.findByDeviceCode(deviceCode)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown or expired authorization request."));
    }

    private void expireIfDue(CliDeviceAuth a) {
        if (CliDeviceAuth.PENDING.equals(a.getStatus()) && a.getExpiresAt().isBefore(OffsetDateTime.now())) {
            a.setStatus(CliDeviceAuth.EXPIRED);
            repo.save(a);
        }
    }

    private String randomUrlSafe(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private String randomUserCode() {
        StringBuilder sb = new StringBuilder(9);
        for (int i = 0; i < 8; i++) {
            if (i == 4) sb.append('-');
            sb.append(USER_CODE_ALPHABET.charAt(random.nextInt(USER_CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
