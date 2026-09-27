SET search_path TO ares, public;

ALTER TABLE engagement_type ADD COLUMN IF NOT EXISTS description TEXT;
