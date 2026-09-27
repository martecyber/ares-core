-- Uninstalling a plugin used to only mark it for a boot-time purge (unlink-while-open on Linux
-- makes it safe to delete the JAR immediately instead — see PluginLoader's own doc comment), so
-- the "uninstalled" flag is no longer needed. Any row still marked true when this runs predates
-- that change and was already headed for deletion on the very next boot anyway.
DELETE FROM ares.plugin WHERE uninstalled = TRUE;
ALTER TABLE ares.plugin DROP COLUMN uninstalled;
