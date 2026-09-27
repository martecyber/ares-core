-- HTTP request/response samples for web_endpoint assets
CREATE TABLE ares.web_endpoint_http_sample (
    id               BIGSERIAL PRIMARY KEY,
    asset_id         BIGINT       NOT NULL REFERENCES ares.asset(id) ON DELETE CASCADE,
    label            VARCHAR(255),
    request_content  TEXT,
    response_content TEXT,
    notes            TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ
);

CREATE INDEX idx_web_endpoint_http_sample_asset_id ON ares.web_endpoint_http_sample(asset_id);
