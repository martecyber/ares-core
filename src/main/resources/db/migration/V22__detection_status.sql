SET search_path TO ares, public;

-- Migrate existing statuses to the new model
UPDATE detection SET status = 'new'   WHERE status = 'open';
UPDATE detection SET status = 'solved' WHERE status = 'closed';

-- Fix the column default so future inserts default to 'new'
ALTER TABLE detection ALTER COLUMN status SET DEFAULT 'new';
