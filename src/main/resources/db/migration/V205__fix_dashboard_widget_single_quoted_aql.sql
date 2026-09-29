-- DashboardService's default widget seeds used single-quoted string literals (e.g. "priority ==
-- 'P0'"), but AqlLexer only recognizes double-quoted strings — any already-persisted dashboard
-- created before this was fixed has broken AQL baked into its widgets. Retroactively repairs the
-- known broken literal values in ares.dashboard_widget.config.

UPDATE ares.dashboard_widget
SET config = jsonb_set(config, '{aql}', to_jsonb(replace(config->>'aql', '''P0''', '"P0"')))
WHERE config->>'aql' LIKE '%''P0''%';

UPDATE ares.dashboard_widget
SET config = jsonb_set(config, '{aql}', to_jsonb(replace(config->>'aql', '''P1''', '"P1"')))
WHERE config->>'aql' LIKE '%''P1''%';

UPDATE ares.dashboard_widget
SET config = jsonb_set(config, '{aql}', to_jsonb(replace(config->>'aql', '''P2''', '"P2"')))
WHERE config->>'aql' LIKE '%''P2''%';

UPDATE ares.dashboard_widget
SET config = jsonb_set(config, '{aql}', to_jsonb(replace(config->>'aql', '''P3''', '"P3"')))
WHERE config->>'aql' LIKE '%''P3''%';

UPDATE ares.dashboard_widget
SET config = jsonb_set(config, '{aql}', to_jsonb(replace(config->>'aql', '''new''', '"new"')))
WHERE config->>'aql' LIKE '%''new''%';
