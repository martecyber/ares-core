SET search_path TO ares, public;

CREATE TABLE detection_score (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    detection_id BIGINT         NOT NULL REFERENCES detection(id) ON DELETE CASCADE,
    type_id      BIGINT         NOT NULL REFERENCES finding_score_type(id),
    score        NUMERIC(3,1)   NOT NULL,
    metadata     JSONB,
    is_default   BOOLEAN        NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_detection_score_detection ON detection_score(detection_id);
