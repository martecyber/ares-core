-- V163: extends the tag catalog (V117/V162) to findings — same shape as detection_tag (V118).
SET search_path TO ares, public;

CREATE TABLE finding_tag (
    finding_id BIGINT      NOT NULL REFERENCES finding(id) ON DELETE CASCADE,
    tag_id     BIGINT      NOT NULL REFERENCES tag(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (finding_id, tag_id)
);
CREATE INDEX ix_finding_tag_tag ON finding_tag(tag_id);
