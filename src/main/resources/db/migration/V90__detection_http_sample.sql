-- HTTP request/response samples for detection records.
-- Mirrors web_endpoint_http_sample (V48) but owned by a detection instead of an asset.
-- Each row is one request/response pair; a detection may have several.
CREATE TABLE ares.detection_http_sample (
    id               BIGSERIAL PRIMARY KEY,
    detection_id     BIGINT       NOT NULL REFERENCES ares.detection(id) ON DELETE CASCADE,
    label            VARCHAR(255),
    request_content  TEXT,
    response_content TEXT,
    notes            TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ
);

CREATE INDEX idx_detection_http_sample_detection_id ON ares.detection_http_sample(detection_id);
