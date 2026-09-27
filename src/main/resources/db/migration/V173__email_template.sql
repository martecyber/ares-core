SET search_path TO ares, public;

-- ── Email Templates (KB) ──────────────────────────────────────────────────────
-- Reusable HTML email bodies, referenced by the Workflows ACTION_NOTIFICATION node when its
-- integration is email-kind. {{var}} placeholders in subject_template/html_content are resolved
-- against the workflow run's context at send time (see MessagingTemplate.renderHtmlSafe).
-- html_content is sanitized server-side on every save (EmailTemplateService) — never trust the
-- editor's client-side sanitization alone.

CREATE TABLE ares.email_template (
    id               BIGSERIAL    PRIMARY KEY,
    name             VARCHAR(160) NOT NULL,
    subject_template VARCHAR(300),
    html_content     TEXT,
    creator_id       BIGINT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
