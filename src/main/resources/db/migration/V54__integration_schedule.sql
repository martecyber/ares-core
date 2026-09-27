CREATE TABLE ares.integration_schedule (
    id              BIGSERIAL PRIMARY KEY,
    engagement_id   BIGINT      NOT NULL REFERENCES ares.engagement(id) ON DELETE CASCADE,
    integration_id  BIGINT      NOT NULL,
    capability      VARCHAR(50) NOT NULL,
    cron_expression VARCHAR(100) NOT NULL,
    enabled         BOOLEAN     NOT NULL DEFAULT TRUE,
    last_run_at     TIMESTAMPTZ,
    next_run_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX ix_integration_schedule_eng   ON ares.integration_schedule(engagement_id);
CREATE INDEX ix_integration_schedule_next  ON ares.integration_schedule(next_run_at) WHERE enabled = TRUE;
