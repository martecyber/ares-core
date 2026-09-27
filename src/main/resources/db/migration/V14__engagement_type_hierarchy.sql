SET search_path TO ares, public;

-- Mark types as system-managed (root types are immutable)
ALTER TABLE engagement_type ADD COLUMN is_system BOOLEAN NOT NULL DEFAULT FALSE;

-- Optional free-text description for each type
ALTER TABLE engagement_type ADD COLUMN description TEXT;

-- Insert root types
INSERT INTO engagement_type (name, code, is_system, description) VALUES
    ('Assessment', 'ASSESS',  TRUE, 'Point-in-time security assessment. Engagements under this type produce a formal report with findings and remediation guidance.'),
    ('Monitoring',  'MONITOR', TRUE, 'Continuous or recurring monitoring engagement. Engagements under this type track detections over time and generate alerts rather than a static report.');
