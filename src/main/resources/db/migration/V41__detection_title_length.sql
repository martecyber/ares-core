-- Fix detection.title column to match entity definition (length = 300).
-- V6 created it as VARCHAR(100); Tenable plugin names can exceed 100 characters.
ALTER TABLE ares.detection
    ALTER COLUMN title TYPE VARCHAR(300);
