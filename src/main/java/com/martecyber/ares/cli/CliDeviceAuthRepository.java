package com.martecyber.ares.cli;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface CliDeviceAuthRepository extends JpaRepository<CliDeviceAuth, Long> {

    Optional<CliDeviceAuth> findByDeviceCode(String deviceCode);

    void deleteByExpiresAtBefore(OffsetDateTime cutoff);
}
