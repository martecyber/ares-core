package com.martecyber.ares.auth;

import com.martecyber.ares.auth.dto.LoginResponse;
import com.martecyber.ares.auth.dto.UserDto;
import com.martecyber.ares.users.User;
import com.martecyber.ares.users.UserRepository;
import com.martecyber.ares.users.UserRole;
import com.martecyber.ares.users.UserRoleRepository;
import jakarta.transaction.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;

@Service
public class AuthService {

    private final UserRepository users;
    private final UserRefreshTokenRepository refreshTokens;
    private final UserRoleRepository userRoles;
    private final UserMfaDeviceRepository mfaDevices;
    private final TotpService totp;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwt;
    private final JwtProperties jwtProps;
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserRepository users,
                       UserRefreshTokenRepository refreshTokens,
                       UserRoleRepository userRoles,
                       UserMfaDeviceRepository mfaDevices,
                       TotpService totp,
                       PasswordEncoder passwordEncoder,
                       JwtService jwt,
                       JwtProperties jwtProps) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.userRoles = userRoles;
        this.mfaDevices = mfaDevices;
        this.totp = totp;
        this.passwordEncoder = passwordEncoder;
        this.jwt = jwt;
        this.jwtProps = jwtProps;
    }

    @Transactional
    public LoginResponse login(String email, String password, String totpCode) {
        User user = users.findByEmailIgnoreCase(email)
            .orElseThrow(InvalidCredentialsException::new);

        if (!"active".equals(user.getStatus())) {
            throw new InvalidCredentialsException();
        }
        byte[] storedHash = user.getPasswordHash();
        if (storedHash == null) throw new InvalidCredentialsException();
        String storedHashStr = new String(storedHash, StandardCharsets.UTF_8);
        if (!passwordEncoder.matches(password, storedHashStr)) {
            throw new InvalidCredentialsException();
        }

        List<UserMfaDevice> devices = mfaDevices.findByUserId(user.getId());
        if (user.isMfaEnforced() && !devices.isEmpty()) {
            if (totpCode == null || totpCode.isBlank()) {
                throw new MfaRequiredException();
            }
            boolean valid = devices.stream()
                .filter(d -> "totp".equalsIgnoreCase(d.getType()))
                .anyMatch(d -> totp.verify(d.getSecretCiphertext(), totpCode));
            if (!valid) throw new InvalidCredentialsException();
        }

        List<String> roleCodes = loadRoleCodes(user.getId());
        List<String> permissionCodes = loadPermissionCodes(user.getId());
        String accessToken = jwt.issueAccessToken(user.getId(), user.getEmail(), roleCodes, permissionCodes);
        String refreshToken = issueRefreshToken(user.getId(), null);

        return new LoginResponse(accessToken, refreshToken, toDto(user, roleCodes, permissionCodes));
    }

    @Transactional
    public LoginResponse refresh(String providedRefreshToken) {
        byte[] hash = sha256(providedRefreshToken);
        UserRefreshToken stored = refreshTokens.findByTokenHash(hash)
            .orElseThrow(InvalidCredentialsException::new);

        OffsetDateTime now = OffsetDateTime.now();
        if (!stored.isActive(now)) {
            throw new InvalidCredentialsException();
        }

        stored.setRevokedAt(now);
        refreshTokens.save(stored);

        User user = users.findById(stored.getUserId())
            .orElseThrow(InvalidCredentialsException::new);
        List<String> roleCodes = loadRoleCodes(user.getId());
        List<String> permissionCodes = loadPermissionCodes(user.getId());

        String newAccess = jwt.issueAccessToken(user.getId(), user.getEmail(), roleCodes, permissionCodes);
        String newRefresh = issueRefreshToken(user.getId(), stored.getId());

        return new LoginResponse(newAccess, newRefresh, toDto(user, roleCodes, permissionCodes));
    }

    @Transactional
    public void logout(String providedRefreshToken) {
        if (providedRefreshToken == null || providedRefreshToken.isBlank()) return;
        byte[] hash = sha256(providedRefreshToken);
        refreshTokens.findByTokenHash(hash).ifPresent(t -> {
            if (t.getRevokedAt() == null) {
                t.setRevokedAt(OffsetDateTime.now());
                refreshTokens.save(t);
            }
        });
    }

    public UserDto loadCurrent(Long userId) {
        User user = users.findById(userId)
            .orElseThrow(InvalidCredentialsException::new);
        return toDto(user, loadRoleCodes(userId), loadPermissionCodes(userId));
    }

    private List<String> loadRoleCodes(Long userId) {
        return userRoles.findByUserId(userId).stream()
            .map(UserRole::getRole)
            .filter(r -> r != null)
            .map(r -> r.getCode())
            .distinct()
            .toList();
    }

    /** Effective permission codes granted via any of the user's roles (roles_permission) —
     *  embedded in the JWT so @PreAuthorize("hasAuthority(...)") can gate fine-grained
     *  actions, alongside the coarser ROLE_* authorities already in use everywhere else. */
    private List<String> loadPermissionCodes(Long userId) {
        return userRoles.findByUserId(userId).stream()
            .map(UserRole::getRole)
            .filter(r -> r != null)
            .flatMap(r -> r.getPermissions().stream())
            .map(p -> p.getCode())
            .distinct()
            .toList();
    }

    private String issueRefreshToken(Long userId, Long rotatedFromId) {
        byte[] raw = new byte[48];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        UserRefreshToken entity = new UserRefreshToken();
        entity.setUserId(userId);
        entity.setTokenHash(sha256(token));
        OffsetDateTime now = OffsetDateTime.now();
        entity.setIssuedAt(now);
        entity.setExpiresAt(now.plus(jwtProps.getRefreshTokenTtl()));
        entity.setRotatedFromId(rotatedFromId);
        refreshTokens.save(entity);

        return token;
    }

    private static UserDto toDto(User user, List<String> roleCodes, List<String> permissionCodes) {
        return new UserDto(user.getId(), user.getEmail(), user.getDisplayName(), roleCodes, permissionCodes);
    }

    private static byte[] sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static class InvalidCredentialsException extends RuntimeException {
        public InvalidCredentialsException() { super("Invalid credentials"); }
    }

    public static class MfaRequiredException extends RuntimeException {
        public MfaRequiredException() { super("MFA required"); }
    }
}
