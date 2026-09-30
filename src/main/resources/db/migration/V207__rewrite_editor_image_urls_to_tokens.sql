-- V206 switched editor-images from a sequential-id public URL to an unguessable token, but every
-- piece of already-saved Markdown across the platform still has the OLD /editor-images/{id} URL
-- baked into its text — those would all 404 once the id-based lookup stops working. This rewrites
-- every occurrence, across every column that can hold Markdown authored via MarkdownEditor.vue:
--   ares.research_board.notes                    (plain text)
--   ares.finding_template_field.field_text        (plain text)
--   ares.testing_procedure.content                (plain text)
--   ares.report_field.content                     (plain text)
--   ares.report_field_template.content            (plain text)
--   ares.affection.description                    (plain text)
--   ares.finding.fields                           (jsonb object, values keyed by field-type name)
--   ares.workflow.graph_definition                (jsonb; nodes[].data.config.bodyTemplate for
--   ares.workflow_template.graph_definition         ACTION_NOTIFICATION nodes only)
--
-- The id is matched with a negative lookahead for a following digit (Postgres's ARE regex flavor
-- supports this) so id=1 never also matches inside id=12's URL.

DO $$
DECLARE
    img RECORD;
    old_pattern TEXT;
    new_ref TEXT;
BEGIN
    FOR img IN SELECT id, token FROM ares.editor_image LOOP
        old_pattern := '/editor-images/' || img.id || '(?![0-9])';
        new_ref := '/editor-images/' || img.token;

        UPDATE ares.research_board
        SET notes = regexp_replace(notes, old_pattern, new_ref, 'g')
        WHERE notes ~ old_pattern;

        UPDATE ares.finding_template_field
        SET field_text = regexp_replace(field_text, old_pattern, new_ref, 'g')
        WHERE field_text ~ old_pattern;

        UPDATE ares.testing_procedure
        SET content = regexp_replace(content, old_pattern, new_ref, 'g')
        WHERE content ~ old_pattern;

        UPDATE ares.report_field
        SET content = regexp_replace(content, old_pattern, new_ref, 'g')
        WHERE content ~ old_pattern;

        UPDATE ares.report_field_template
        SET content = regexp_replace(content, old_pattern, new_ref, 'g')
        WHERE content ~ old_pattern;

        UPDATE ares.affection
        SET description = regexp_replace(description, old_pattern, new_ref, 'g')
        WHERE description ~ old_pattern;

        -- finding.fields: {"<field-type-name>": "markdown string", ...} — rebuild the object with
        -- each string value rewritten, leaving keys and non-matching values untouched.
        UPDATE ares.finding f
        SET fields = (
            SELECT jsonb_object_agg(
                kv.key,
                CASE WHEN kv.value ~ old_pattern
                     THEN to_jsonb(regexp_replace(kv.value, old_pattern, new_ref, 'g'))
                     ELSE to_jsonb(kv.value)
                END
            )
            FROM jsonb_each_text(f.fields) AS kv
        )
        WHERE f.fields IS NOT NULL
          AND EXISTS (SELECT 1 FROM jsonb_each_text(f.fields) AS kv WHERE kv.value ~ old_pattern);

        -- workflow(_template).graph_definition: rewrite only ACTION_NOTIFICATION nodes' own
        -- data.config.bodyTemplate, leaving every other node and every other field untouched.
        UPDATE ares.workflow w
        SET graph_definition = jsonb_set(
            w.graph_definition,
            '{nodes}',
            (
                SELECT jsonb_agg(
                    CASE
                        WHEN node->>'type' = 'ACTION_NOTIFICATION'
                             AND (node #>> '{data,config,bodyTemplate}') ~ old_pattern
                        THEN jsonb_set(node, '{data,config,bodyTemplate}',
                            to_jsonb(regexp_replace(node #>> '{data,config,bodyTemplate}', old_pattern, new_ref, 'g')))
                        ELSE node
                    END
                )
                FROM jsonb_array_elements(w.graph_definition->'nodes') AS node
            )
        )
        WHERE w.graph_definition IS NOT NULL
          AND w.graph_definition ? 'nodes'
          AND EXISTS (
              SELECT 1 FROM jsonb_array_elements(w.graph_definition->'nodes') AS node
              WHERE node->>'type' = 'ACTION_NOTIFICATION'
                AND (node #>> '{data,config,bodyTemplate}') ~ old_pattern
          );

        UPDATE ares.workflow_template wt
        SET graph_definition = jsonb_set(
            wt.graph_definition,
            '{nodes}',
            (
                SELECT jsonb_agg(
                    CASE
                        WHEN node->>'type' = 'ACTION_NOTIFICATION'
                             AND (node #>> '{data,config,bodyTemplate}') ~ old_pattern
                        THEN jsonb_set(node, '{data,config,bodyTemplate}',
                            to_jsonb(regexp_replace(node #>> '{data,config,bodyTemplate}', old_pattern, new_ref, 'g')))
                        ELSE node
                    END
                )
                FROM jsonb_array_elements(wt.graph_definition->'nodes') AS node
            )
        )
        WHERE wt.graph_definition IS NOT NULL
          AND wt.graph_definition ? 'nodes'
          AND EXISTS (
              SELECT 1 FROM jsonb_array_elements(wt.graph_definition->'nodes') AS node
              WHERE node->>'type' = 'ACTION_NOTIFICATION'
                AND (node #>> '{data,config,bodyTemplate}') ~ old_pattern
          );
    END LOOP;
END $$;
