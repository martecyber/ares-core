-- Separate Ares-side timestamps from platform-provided timestamps on synced scope entries.
-- created_at / updated_at remain as Ares housekeeping fields.
-- platform_created_at / platform_updated_at hold the originating platform's timestamps (null for manual entries).
ALTER TABLE ares.engagement_scope_entry
    ADD COLUMN platform_created_at TIMESTAMPTZ,
    ADD COLUMN platform_updated_at TIMESTAMPTZ;
