SET search_path TO ares, public;

ALTER TABLE finding_field_type ADD COLUMN IF NOT EXISTS name VARCHAR(50);

-- Derive slug from title: lowercase, collapse non-alphanumeric runs to underscore, strip trailing underscore
UPDATE finding_field_type
SET name = REGEXP_REPLACE(
    REGEXP_REPLACE(LOWER(title), '[^a-z0-9]+', '_', 'g'),
    '_$', ''
);

ALTER TABLE finding_field_type
    ALTER COLUMN name SET NOT NULL,
    ADD CONSTRAINT uq_finding_field_type_name UNIQUE (name);
