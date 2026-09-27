package com.martecyber.ares.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface UserMfaDeviceRepository extends JpaRepository<UserMfaDevice, Long> {
    List<UserMfaDevice> findByUserId(Long userId);
    void deleteByUserIdAndId(Long userId, Long deviceId);
}
