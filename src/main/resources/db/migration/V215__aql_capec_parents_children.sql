-- AQL: CAPEC's self-relations were renamed parentCapec/childCapec -> parents/children, matching
-- CWE's own parents/children. Rewrite saved queries that use the old names (a relation is always
-- followed by ".<leaf>", so the trailing dot keeps JSON keys like parentCapecIds untouched).

UPDATE ares.dashboard_widget
SET config = regexp_replace(regexp_replace(config::text,
        '\mparentCapec\.', 'parents.', 'g'), '\mchildCapec\.', 'children.', 'g')::jsonb
WHERE config::text ~ '\m(parent|child)Capec\.';

UPDATE ares.project_rule
SET config = regexp_replace(regexp_replace(config::text,
        '\mparentCapec\.', 'parents.', 'g'), '\mchildCapec\.', 'children.', 'g')::jsonb
WHERE config::text ~ '\m(parent|child)Capec\.';

UPDATE ares.workflow_trigger
SET config = regexp_replace(regexp_replace(config::text,
        '\mparentCapec\.', 'parents.', 'g'), '\mchildCapec\.', 'children.', 'g')::jsonb
WHERE config::text ~ '\m(parent|child)Capec\.';

UPDATE ares.workflow
SET graph_definition = regexp_replace(regexp_replace(graph_definition::text,
        '\mparentCapec\.', 'parents.', 'g'), '\mchildCapec\.', 'children.', 'g')::jsonb
WHERE graph_definition::text ~ '\m(parent|child)Capec\.';

UPDATE ares.workflow_template
SET graph_definition = regexp_replace(regexp_replace(graph_definition::text,
        '\mparentCapec\.', 'parents.', 'g'), '\mchildCapec\.', 'children.', 'g')::jsonb
WHERE graph_definition::text ~ '\m(parent|child)Capec\.';
