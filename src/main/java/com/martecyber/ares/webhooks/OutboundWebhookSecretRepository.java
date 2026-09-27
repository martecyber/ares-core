package com.martecyber.ares.webhooks;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OutboundWebhookSecretRepository extends JpaRepository<OutboundWebhookSecret, Long> {
    Optional<OutboundWebhookSecret> findByWorkflowIdAndNodeId(Long workflowId, String nodeId);
    List<OutboundWebhookSecret> findByWorkflowId(Long workflowId);
    void deleteByWorkflowIdAndNodeId(Long workflowId, String nodeId);
}
