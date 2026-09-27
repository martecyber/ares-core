-- V165: extends the tag catalog (V117/V162) to finding templates — same shape as detection_tag
-- (V118). FindingTemplate is a platform-wide catalog entity (no organization_id column at all),
-- so in practice only platform tags (tag.organization_id IS NULL) can ever be assigned here —
-- enforced in FindingTemplateService.assignTag, not at the DB level.
SET search_path TO ares, public;

CREATE TABLE finding_template_tag (
    finding_template_id BIGINT      NOT NULL REFERENCES finding_template(id) ON DELETE CASCADE,
    tag_id               BIGINT      NOT NULL REFERENCES tag(id) ON DELETE CASCADE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (finding_template_id, tag_id)
);
CREATE INDEX ix_finding_template_tag_tag ON finding_template_tag(tag_id);
