-- Workflows implementation plan, Phase F: outbound ACTION_WEBHOOK_CALL, optional HMAC signing.
--
-- The signing secret is deliberately NOT stored in workflow.graph_definition (the node's own
-- JSONB config) -- that column has no encryption, unlike every other secret in this codebase
-- (messaging_integration.config_ciphertext, webhook_endpoint.secret_ciphertext, both AES-256-GCM
-- via CredentialEncryptionService). This table mirrors webhook_endpoint's own (workflow_id,
-- node_id) keying for the same reason: workflow_trigger rows churn on every save, so anything
-- keyed off a trigger/step row id would be unstable -- but node_id is stable as long as the node
-- itself isn't deleted.
--
-- Unlike webhook_endpoint (which must re-display its secret so an admin can paste it into an
-- external system), this secret is authored BY the admin to match what the external system
-- already expects -- so it's write-only from the API's perspective, never read back in plaintext.

CREATE TABLE ares.outbound_webhook_secret (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workflow_id        BIGINT       NOT NULL REFERENCES ares.workflow(id) ON DELETE CASCADE,
    node_id            VARCHAR(64)  NOT NULL,
    secret_ciphertext  BYTEA        NOT NULL,
    secret_iv          BYTEA        NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX ix_outbound_webhook_secret_workflow_node ON ares.outbound_webhook_secret(workflow_id, node_id);
