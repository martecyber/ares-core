-- V117: Tags — a per-organization catalog (name + color) that can be attached to items.
-- Only assets get a join table for now; adding tags to another item type later (e.g.
-- detections) just means adding its own {entity}_tag join table, without touching this
-- catalog — mirrors the existing reference_entry + reference_entry_finding/_detection shape.
SET search_path TO ares, public;

CREATE TABLE tag (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id BIGINT      NOT NULL REFERENCES organization(id) ON DELETE CASCADE,
    name            VARCHAR(50) NOT NULL,
    -- Hex color, e.g. "#4F46E5" — background swatch; UI derives black/white text for contrast.
    color           VARCHAR(7)  NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (organization_id, name)
);
CREATE INDEX ix_tag_org ON tag(organization_id);

CREATE TABLE asset_tag (
    asset_id   BIGINT      NOT NULL REFERENCES asset(id) ON DELETE CASCADE,
    tag_id     BIGINT      NOT NULL REFERENCES tag(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (asset_id, tag_id)
);
CREATE INDEX ix_asset_tag_tag ON asset_tag(tag_id);
