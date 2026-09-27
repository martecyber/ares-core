SET search_path TO ares, public;

-- Lets the frontend (and ProjectTypeController#setDisabled's own guard) tell a type that's
-- merely admin-disabled apart from one that's unavailable because the plugin owning it isn't
-- installed/enabled — see ProjectType.requiredPluginId's own doc. Backfilled for the Bug Hunting
-- rows here since they predate this column; going forward each plugin's own PluginLifecycle sets
-- it directly via ProjectTypeRepository (BugHuntingProjectTypes.ensure).
ALTER TABLE project_type ADD COLUMN required_plugin_id VARCHAR(100);

UPDATE project_type SET required_plugin_id = 'bughunting' WHERE code = 'BH';
UPDATE project_type SET required_plugin_id = 'bughunting-hackerone' WHERE code = 'BH_H1';
UPDATE project_type SET required_plugin_id = 'bughunting-intigriti' WHERE code = 'BH_INTG';
