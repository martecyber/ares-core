SET search_path TO ares, public;

-- Per-engagement configuration for Bug Hunting programmes linked to external platforms
CREATE TABLE engagement_bug_hunting_program (
    engagement_id       BIGINT       NOT NULL PRIMARY KEY
                            REFERENCES engagement(id) ON DELETE CASCADE,
    platform            VARCHAR(20)  NOT NULL,   -- 'bugcrowd'|'yeswehack'|'intigriti'|'hackerone'|'generic'
    integration_id      BIGINT       REFERENCES integration(id) ON DELETE SET NULL,
    program_handle      VARCHAR(255),            -- slug / programme id on the external platform
    sync_enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    sync_interval_hours INT          NOT NULL DEFAULT 24,
    last_sync_at        TIMESTAMPTZ,
    last_sync_status    VARCHAR(30),             -- 'success'|'failed'|'partial'|'never'
    last_sync_error     TEXT,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL
);
