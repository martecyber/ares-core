CREATE TABLE ares.kb_sync_schedule (
    id              BIGSERIAL    PRIMARY KEY,
    sync_type       VARCHAR(50)  NOT NULL,
    cron_expression VARCHAR(100) NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    last_run_at     TIMESTAMPTZ,
    next_run_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX ix_kb_sync_schedule_type ON ares.kb_sync_schedule(sync_type);
CREATE INDEX ix_kb_sync_schedule_next ON ares.kb_sync_schedule(next_run_at) WHERE enabled = TRUE;
