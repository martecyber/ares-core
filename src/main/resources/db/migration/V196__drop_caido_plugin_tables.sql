SET search_path TO ares, public;

-- Removes "Caido (Plugin)" (the browser-side plugin / X-Caido-Token ingest bridge) entirely,
-- per explicit user decision — kept only "Caido (API)" (Ares calling Caido's own GraphQL
-- directly, caido_api_* tables, untouched by this migration). Irreversible: destroys any
-- Caido-Plugin integration/token/grant/task configured in any org. Does NOT touch Detection or
-- Asset rows — those live in the generic tables and keep their existing source_type/tool="caido"
-- value regardless of which of the two Caido flavors originally created them.
DROP TABLE IF EXISTS caido_task;
DROP TABLE IF EXISTS caido_integration_grant;
DROP TABLE IF EXISTS caido_integration;
