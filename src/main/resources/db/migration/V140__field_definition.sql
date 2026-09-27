-- AQL groundwork: a shared catalog declaring which dynamic/custom-field keys are legal per
-- entity, so the AQL compiler can validate/type `metadata.osVersion`-style field access instead
-- of treating jsonb as an untyped blob. organization_id NULL means a platform-wide default
-- definition (applies to every org unless overridden by an org-scoped row with the same
-- entity_type/asset_type/field_key). asset_type is only meaningful when entity_type='asset' and
-- the field is specific to one asset type (e.g. 'osVersion' only for type='host'); NULL means it
-- applies to every asset type. Purely additive — no existing table or data is touched here.

CREATE TABLE ares.field_definition (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id BIGINT REFERENCES ares.organization(id) ON DELETE CASCADE,
    entity_type     VARCHAR(20) NOT NULL,   -- 'asset' | 'finding'
    asset_type      VARCHAR(50),
    field_key       VARCHAR(100) NOT NULL,
    title           VARCHAR(100) NOT NULL,
    description     TEXT,
    data_type       VARCHAR(20) NOT NULL DEFAULT 'string',  -- string | number | boolean | date | enum
    is_required     BOOLEAN NOT NULL DEFAULT FALSE,
    allowed_values  JSONB,
    sort_order      INT NOT NULL DEFAULT 100,
    is_system       BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Plain UNIQUE() treats NULL as distinct, which would let duplicate platform-wide (NULL org)
-- or type-wide (NULL asset_type) definitions slip in — coalesce both to a sentinel so the
-- constraint actually holds for those rows too.
CREATE UNIQUE INDEX ux_field_definition_key
    ON ares.field_definition(COALESCE(organization_id, -1), entity_type, COALESCE(asset_type, ''), field_key);

CREATE INDEX ix_field_definition_lookup ON ares.field_definition(entity_type, asset_type, organization_id);
