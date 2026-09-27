SET search_path TO ares, public;

-- Bug Hunting (BH/BH_BC/BH_YWH/BH_INTG/BH_H1) is no longer built into ares-core — its entity,
-- table, controller, service and sync jobs all moved into the ares-plugin-bughunting plugin (plus
-- the two platform plugins depending on it). A base install with none of those plugins present
-- should only offer ASSESS/MONITOR/RETEST, so every BH project_type row is disabled here rather
-- than deleted (no cascade, no risk to existing project rows still referencing one of these types
-- by id) — installing ares-plugin-bughunting re-enables BH, and each platform plugin re-enables
-- its own subtype, via PluginLifecycle.onInstall.
UPDATE project_type SET disabled = TRUE WHERE code IN ('BH', 'BH_BC', 'BH_YWH', 'BH_INTG', 'BH_H1');

-- The table itself is now owned by ares-plugin-bughunting (created via its own
-- PluginLifecycle.onInstall, raw SQL, no JPA — see that plugin's own migration note). Dropping it
-- here is explicitly destructive for any existing Bug Hunting programme configuration on an
-- instance that hasn't installed the plugin yet — accepted: of the instances that have any BH
-- project at all, only a disposable test environment has ever configured a programme, and
-- installing the plugin recreates the table fresh.
DROP TABLE IF EXISTS project_bug_hunting_program;
