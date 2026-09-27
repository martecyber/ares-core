package com.martecyber.ares.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface UserApiTokenRepository extends JpaRepository<UserApiToken, Long> {
    List<UserApiToken> findByUserId(Long userId);
    Optional<UserApiToken> findByTokenHash(String tokenHash);
    void deleteByUserIdAndId(Long userId, Long tokenId);
}
