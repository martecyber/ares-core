-- Reusable named email contacts per organization (e.g. "Ticketing", "Client CISO") — referenced
-- by "email_recipients" Rules of Engagement (ares.project_rule) to auto-populate To/CC/BCC when
-- reporting a finding by email, without retyping the same address in every project.
CREATE TABLE ares.organization_contact (
    id              BIGSERIAL PRIMARY KEY,
    organization_id BIGINT       NOT NULL REFERENCES ares.organization(id) ON DELETE CASCADE,
    name            VARCHAR(200) NOT NULL,
    email           VARCHAR(320) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ
);

CREATE INDEX idx_organization_contact_org ON ares.organization_contact(organization_id);
