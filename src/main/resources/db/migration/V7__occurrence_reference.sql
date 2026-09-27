SET search_path TO ares, public;

CREATE TABLE occurrence (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    finding_id BIGINT NOT NULL REFERENCES finding(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX ix_occurrence_finding ON occurrence(finding_id);

CREATE TABLE occurrence_detection (
    occurrence_id BIGINT NOT NULL REFERENCES occurrence(id) ON DELETE CASCADE,
    detection_id  BIGINT NOT NULL REFERENCES detection(id)  ON DELETE CASCADE,
    PRIMARY KEY (occurrence_id, detection_id)
);

CREATE TABLE occurrence_asset (
    occurrence_id BIGINT NOT NULL REFERENCES occurrence(id) ON DELETE CASCADE,
    asset_id      BIGINT NOT NULL REFERENCES asset(id)      ON DELETE CASCADE,
    PRIMARY KEY (occurrence_id, asset_id)
);

CREATE TABLE reference_entry_finding (
    reference_entry_id BIGINT NOT NULL REFERENCES reference_entry(id) ON DELETE CASCADE,
    finding_id         BIGINT NOT NULL REFERENCES finding(id)          ON DELETE CASCADE,
    PRIMARY KEY (reference_entry_id, finding_id)
);

CREATE TABLE reference_entry_detection (
    reference_entry_id BIGINT NOT NULL REFERENCES reference_entry(id) ON DELETE CASCADE,
    detection_id       BIGINT NOT NULL REFERENCES detection(id)        ON DELETE CASCADE,
    PRIMARY KEY (reference_entry_id, detection_id)
);

CREATE TABLE reference_entry_finding_template (
    reference_entry_id  BIGINT NOT NULL REFERENCES reference_entry(id)  ON DELETE CASCADE,
    finding_template_id BIGINT NOT NULL REFERENCES finding_template(id) ON DELETE CASCADE,
    PRIMARY KEY (reference_entry_id, finding_template_id)
);
