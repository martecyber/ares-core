package com.martecyber.ares.webhooks;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, Long> {
    Optional<WebhookEndpoint> findByToken(String token);
    Optional<WebhookEndpoint> findByWorkflowIdAndNodeId(Long workflowId, String nodeId);
    List<WebhookEndpoint> findByWorkflowId(Long workflowId);
    void deleteByWorkflowIdAndNodeId(Long workflowId, String nodeId);
}
