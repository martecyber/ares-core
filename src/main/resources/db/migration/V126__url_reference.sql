-- Adds support for plain external-URL references (alongside the existing CVE/CWE/
-- ATT&CK/CAPEC/OWASP KB-backed references): a reference_entry can now point to an
-- arbitrary website instead of a knowledge-base record. Title is widened past 50
-- chars since page titles (unlike "CVE-2024-1234"-style KB ids) run much longer,
-- and it's left blank rather than NULL when neither the user nor the page supply one.

ALTER TABLE reference_entry ALTER COLUMN title TYPE TEXT;

ALTER TABLE reference_entry ADD COLUMN url TEXT;
ALTER TABLE reference_entry ADD COLUMN favicon_bucket VARCHAR(100);
ALTER TABLE reference_entry ADD COLUMN favicon_object_key VARCHAR(300);
ALTER TABLE reference_entry ADD COLUMN favicon_content_type VARCHAR(100);

CREATE INDEX ix_re_url ON reference_entry(url) WHERE url IS NOT NULL;

INSERT INTO reference_catalog (code, title)
VALUES ('URL', 'External URL')
ON CONFLICT (code) DO NOTHING;
