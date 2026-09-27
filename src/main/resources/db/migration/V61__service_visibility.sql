-- Service visibility: for each SERVICE asset, records which source IPs can see it and in what state.
-- Populated by file imports from port-scan tools (nmap, masscan, naabu) when the user supplies a sourceIp.

CREATE TABLE ares.service_visibility (
    id                BIGSERIAL PRIMARY KEY,
    service_asset_id  BIGINT       NOT NULL REFERENCES ares.asset(id) ON DELETE CASCADE,
    source_ip         VARCHAR(45)  NOT NULL,
    state             VARCHAR(16)  NOT NULL,
    first_seen        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_seen         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_import_id    BIGINT       REFERENCES ares.scan_import(id) ON DELETE SET NULL,
    CONSTRAINT uq_service_visibility UNIQUE (service_asset_id, source_ip),
    CONSTRAINT ck_service_visibility_state CHECK (state IN ('OPEN','FILTERED','CLOSED'))
);

CREATE INDEX idx_service_visibility_service ON ares.service_visibility(service_asset_id);
CREATE INDEX idx_service_visibility_source  ON ares.service_visibility(source_ip);

-- Audit columns on scan_import: which source IP the operator declared + how many visibility rows were upserted.
ALTER TABLE ares.scan_import ADD COLUMN source_ip VARCHAR(45);
ALTER TABLE ares.scan_import ADD COLUMN visibility_recorded INT NOT NULL DEFAULT 0;
