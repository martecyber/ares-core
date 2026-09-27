-- URL (or ares-ui-relative path, e.g. "/img/sources/shodan.svg") for the plugin's own icon — as
-- configured in the JAR's plugin.json manifest, shown wherever this plugin's integration type
-- appears (the Plugins admin list, the "new integration" picker, and any "source" badge that falls
-- back to a dynamically-registered type — see ares-ui's tool-icons.ts).
ALTER TABLE ares.plugin ADD COLUMN icon TEXT;
