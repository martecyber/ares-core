SET search_path TO ares, public;

-- The list of Caido projects available on the linked Caido instance, reported by the plugin
-- on heartbeat. Used to populate the Caido-project dropdown when creating a task (Ares can't
-- query Caido directly). Shape: [{"id": "...", "name": "..."}].
ALTER TABLE caido_integration ADD COLUMN known_projects JSONB NOT NULL DEFAULT '[]'::jsonb;
