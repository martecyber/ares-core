-- Workflow templates are stripped of scope-bound resource ids (see WorkflowTemplateGraphStripper)
-- so the same template row can be instantiated anywhere, but the node *types* it holds are still
-- only valid at one scope level (e.g. ACTION_AGENT_TASK/ACTION_SYNC are project-only) — so every
-- template needs to declare which level it targets, same vocabulary as workflow.scope_kind. This
-- is a backfill default only (no re-validation runs against existing rows here) — 'platform' just
-- picks the most-generic label for pre-existing templates; an admin can narrow it down via the new
-- template editor.
ALTER TABLE ares.workflow_template ADD COLUMN scope_kind VARCHAR(20) NOT NULL DEFAULT 'platform';
