SET search_path TO ares, public;

-- Bug Hunting master type
INSERT INTO engagement_type (name, code, is_system, description) VALUES
    ('Bug Hunting', 'BH', TRUE,
     'Bug bounty and vulnerability disclosure programme engagement. Generic subtype — no external platform API linked; scope is managed manually.');

-- Platform-specific subtypes (reference the master by code)
INSERT INTO engagement_type (name, code, supertype_id, is_system, description)
SELECT 'Bug Hunting — Bugcrowd', 'BH_BC', id, TRUE,
       'Bug Hunting engagement linked to a Bugcrowd programme. Scope is automatically synchronised from the Bugcrowd API.'
FROM engagement_type WHERE code = 'BH';

INSERT INTO engagement_type (name, code, supertype_id, is_system, description)
SELECT 'Bug Hunting — YesWeHack', 'BH_YWH', id, TRUE,
       'Bug Hunting engagement linked to a YesWeHack programme. Scope is automatically synchronised from the YesWeHack API.'
FROM engagement_type WHERE code = 'BH';

INSERT INTO engagement_type (name, code, supertype_id, is_system, description)
SELECT 'Bug Hunting — Intigriti', 'BH_INTG', id, TRUE,
       'Bug Hunting engagement linked to an Intigriti programme. Scope is automatically synchronised from the Intigriti API.'
FROM engagement_type WHERE code = 'BH';

INSERT INTO engagement_type (name, code, supertype_id, is_system, description)
SELECT 'Bug Hunting — HackerOne', 'BH_H1', id, TRUE,
       'Bug Hunting engagement linked to a HackerOne programme. Scope is automatically synchronised from the HackerOne API.'
FROM engagement_type WHERE code = 'BH';
