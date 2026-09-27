-- Workflows implementation plan, Phase C: inbound webhook triggers. Ships as a genuinely
-- general-purpose primitive (not nested under the workflows schema conceptually, even though the
-- only owner today is a TRIGGER_WEBHOOK graph node) -- see WebhookSignatureService.
--
-- One row per TRIGGER_WEBHOOK node, keyed by (workflow_id, node_id) rather than
-- workflow_trigger.id: WorkflowService.syncTriggers() fully deletes+reinserts workflow_trigger
-- rows on every workflow save (they carry no independent state besides nextRunAt), which would
-- silently rotate the token/secret -- and therefore break every external caller already
-- configured with the URL -- on every unrelated graph edit. node_id is stable across saves as
-- long as the node itself isn't deleted, matching workflow_trigger's own node_id convention.

CREATE TABLE ares.webhook_endpoint (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- Public URL slug: POST /api/v1/webhooks/in/{token}. Random, not sequential/guessable.
    token              VARCHAR(64)  NOT NULL UNIQUE,
    workflow_id        BIGINT       NOT NULL REFERENCES ares.workflow(id) ON DELETE CASCADE,
    -- References graph_definition's node id, not an FK -- same convention as workflow_trigger.node_id.
    node_id            VARCHAR(64)  NOT NULL,
    -- AES-256-GCM via the existing CredentialEncryptionService (same scheme as
    -- messaging_integration.config_ciphertext/config_iv) -- no new crypto path.
    secret_ciphertext  BYTEA        NOT NULL,
    secret_iv          BYTEA        NOT NULL,
    enabled            BOOLEAN      NOT NULL DEFAULT TRUE,
    request_count      BIGINT       NOT NULL DEFAULT 0,
    last_triggered_at  TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX ix_webhook_endpoint_workflow_node ON ares.webhook_endpoint(workflow_id, node_id);
