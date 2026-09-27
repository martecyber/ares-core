package com.martecyber.ares.detectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DetectorPluginRepository extends JpaRepository<DetectorPlugin, Long> {
    Page<DetectorPlugin> findByToolId(Long toolId, Pageable pageable);
}
