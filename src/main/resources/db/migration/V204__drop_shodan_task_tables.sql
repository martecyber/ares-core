-- Removes the "shodan-task" subsystem entirely (org-wide HOST_INFO/SEARCH asset enrichment, its
-- own dedicated tables). Confirmed dead weight: fully functional backend, but unreachable from
-- the UI (its own page was already removed; ShodanManagementDialog.vue was orphaned, imported by
-- nothing) and fully superseded by ares-plugin-shodan's project-scoped, detection-creating
-- enrichment. Explicit user decision, same precedent as V196__drop_caido_plugin_tables.sql.
DROP TABLE IF EXISTS ares.shodan_task;
DROP TABLE IF EXISTS ares.shodan_integration;
