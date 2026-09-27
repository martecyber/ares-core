-- Platform-scoped notification dedup: one row per (binding, event_key).
-- For scheduled triggers (agent_offline, pool_low, stalled) the code checks
-- whether a row with the same key was inserted within the binding's cooldown
-- window. For event-driven triggers (enrolled, task_failed) it checks for any
-- row (fires exactly once per unique event_key).
CREATE TABLE ares.messaging_platform_sent (
    id          BIGSERIAL PRIMARY KEY,
    binding_id  BIGINT       NOT NULL REFERENCES ares.messaging_event_binding(id) ON DELETE CASCADE,
    event_key   VARCHAR(120) NOT NULL,
    sent_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_mps_binding_key_time
    ON ares.messaging_platform_sent (binding_id, event_key, sent_at DESC);
