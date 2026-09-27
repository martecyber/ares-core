-- Optional light-theme override for ares.plugin.icon — same "icon" (dark/default) + "icon_light"
-- (light-theme override, used only when set) convention ares-ui's own tool-icons.ts already
-- follows for built-in tools (see LIGHT_ICON_SRC there).
ALTER TABLE ares.plugin ADD COLUMN icon_light TEXT;
