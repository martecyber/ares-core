-- User-owned display name for any asset type. The identifier stays the import dedup key
-- (org, type, identifier), so renaming must never touch it; imports never write this column.
ALTER TABLE asset ADD COLUMN display_name TEXT;

-- Web applications, networks and app types kept a hand-entered "Display name" in metadata.name
-- (no importer writes that key for these types). Move it into the column and drop the copy.
UPDATE asset
   SET display_name = NULLIF(BTRIM(metadata ->> 'name'), ''),
       metadata     = metadata - 'name'
 WHERE type IN ('web_application', 'network', 'android_app', 'ios_app', 'windows_app')
   AND metadata IS NOT NULL
   AND jsonb_typeof(metadata) = 'object'
   AND metadata ? 'name';
