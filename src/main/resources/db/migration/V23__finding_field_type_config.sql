SET search_path TO ares, public;

-- Extend finding_field_type with configuration columns
ALTER TABLE finding_field_type
    ADD COLUMN IF NOT EXISTS is_system   BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS is_required BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS sort_order  INT     NOT NULL DEFAULT 100;

-- Add "Short description" as the first system+required field
INSERT INTO finding_field_type (title, description, is_system, is_required, sort_order, created_at)
VALUES ('Short description', 'One-line summary of the finding', TRUE, TRUE, 0, NOW());

-- Mark existing system fields as required=true, system=true
UPDATE finding_field_type SET is_system = TRUE, is_required = TRUE, sort_order = 1
WHERE title = 'Description';

UPDATE finding_field_type SET is_system = TRUE, is_required = TRUE, sort_order = 2
WHERE title = 'Impact';

UPDATE finding_field_type SET is_system = TRUE, is_required = TRUE, sort_order = 3
WHERE title = 'Remediation';

-- Give the remaining non-system fields a sensible order
UPDATE finding_field_type SET sort_order = 10 WHERE title = 'Steps to Reproduce';
UPDATE finding_field_type SET sort_order = 20 WHERE title = 'Evidence';
UPDATE finding_field_type SET sort_order = 30 WHERE title = 'CVSS Vector';
