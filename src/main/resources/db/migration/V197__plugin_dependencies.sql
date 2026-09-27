SET search_path TO ares, public;

-- Plugin-to-plugin dependencies + generic extension points + REST-route ownership (see
-- PluginManifest/PluginLoader's own doc comments) — needed for a "base" plugin (e.g.
-- ares-plugin-bughunting) that other plugins depend on and extend.
ALTER TABLE plugin ADD COLUMN depends_on jsonb NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE plugin ADD COLUMN provides_extension_points jsonb NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE plugin ADD COLUMN owned_path_prefixes jsonb NOT NULL DEFAULT '[]'::jsonb;
