-- V118: Extends the tag catalog (V117) to detections — same shape as asset_tag,
-- proving out the "add a new {entity}_tag join table" extension path.
SET search_path TO ares, public;

CREATE TABLE detection_tag (
    detection_id BIGINT      NOT NULL REFERENCES detection(id) ON DELETE CASCADE,
    tag_id       BIGINT      NOT NULL REFERENCES tag(id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (detection_id, tag_id)
);
CREATE INDEX ix_detection_tag_tag ON detection_tag(tag_id);
