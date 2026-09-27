-- Platform-level messaging integrations (Discord/Slack/Telegram/Teams/Generic webhook)
-- with per-scope event bindings and an SLA dedup table.
SET search_path TO ares, public;

CREATE TABLE messaging_integration (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name              VARCHAR(120) NOT NULL UNIQUE,
    kind              VARCHAR(20)  NOT NULL,  -- discord | slack | telegram | teams | webhook
    enabled           BOOLEAN      NOT NULL DEFAULT TRUE,
    -- AES-GCM encrypted JSON: per-kind config (webhook URL, bot token+chat_id, custom headers…)
    config_ciphertext BYTEA        NOT NULL,
    config_iv         BYTEA        NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE messaging_event_binding (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    integration_id  BIGINT       NOT NULL REFERENCES messaging_integration(id) ON DELETE CASCADE,
    -- 'project' or 'organization'. Identifies where the binding lives; scope_id resolves
    -- to projects.id or organization.id accordingly.
    scope_kind      VARCHAR(20)  NOT NULL,
    scope_id        BIGINT       NOT NULL,
    -- 'detection_created' (project-scoped, severity filter) or 'sla_due_soon' (org-scoped, days threshold).
    event_type      VARCHAR(40)  NOT NULL,
    -- JSON payload of event-specific filters. detection_created → {severities:[..]}.
    --                                          sla_due_soon → {daysBefore: int}.
    config          JSONB        NOT NULL DEFAULT '{}',
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_msg_binding_scope ON messaging_event_binding(scope_kind, scope_id);
CREATE INDEX ix_msg_binding_event ON messaging_event_binding(event_type) WHERE enabled = TRUE;

-- Per-binding dedup so daily SLA scans don't re-send the same alert. We keep one
-- row per (binding, finding) the first time we notify; the scheduler skips any
-- pair already present.
CREATE TABLE messaging_sla_sent (
    binding_id BIGINT       NOT NULL REFERENCES messaging_event_binding(id) ON DELETE CASCADE,
    finding_id BIGINT       NOT NULL REFERENCES finding(id) ON DELETE CASCADE,
    sent_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (binding_id, finding_id)
);
