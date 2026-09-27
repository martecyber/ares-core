package com.martecyber.ares.integrations.notifications;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MessagingIntegrationRepository extends JpaRepository<MessagingIntegration, Long> {
    List<MessagingIntegration> findAllByOrderByNameAsc();
}
