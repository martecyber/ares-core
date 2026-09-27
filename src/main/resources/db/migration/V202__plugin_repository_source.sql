SET search_path TO ares, public;

-- Persisted plugin marketplace repositories (see PluginRepositorySource/PluginRepositorySourceService) —
-- a base URL to a static, directory-based repo index the admin's instance can browse/install from.
-- One official row is seeded below; the admin can add more. official=true rows can be disabled but
-- never deleted (enforced in PluginRepositorySourceService, not here).
CREATE TABLE plugin_repository_source (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(150) NOT NULL,
    base_url    TEXT NOT NULL,
    official    BOOLEAN NOT NULL DEFAULT FALSE,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    added_by    BIGINT,
    added_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO plugin_repository_source (name, base_url, official, enabled)
VALUES ('Ares official plugins', 'https://plugins.aresasm.app', TRUE, TRUE);

-- Which repository an installed plugin came from (null for an upload, or a marketplace install
-- made before this column existed) — lets "check for updates" re-query that exact repo instead of
-- only having the one-shot downloadUrl already in source_url.
ALTER TABLE plugin ADD COLUMN repository_source_id BIGINT REFERENCES plugin_repository_source(id) ON DELETE SET NULL;

-- PluginManifest.dependsOn moved from a flat string array (plugin id only) to an object array
-- ({"pluginId","minVersion","maxVersion"}) so a dependency can declare a required version range —
-- upgrade any already-installed plugin row still holding the old shape in place, defensively (no
-- installed plugin declares a non-empty depends_on today, but a real deployment might).
UPDATE plugin
SET depends_on = (
    SELECT jsonb_agg(jsonb_build_object('pluginId', elem, 'minVersion', NULL, 'maxVersion', NULL))
    FROM jsonb_array_elements_text(depends_on) AS elem
)
WHERE jsonb_typeof(depends_on) = 'array'
  AND jsonb_array_length(depends_on) > 0
  AND jsonb_typeof(depends_on -> 0) = 'string';
