-- Workflows Phase H+: the 5 ad hoc recurring-sync systems (KB sync, integration schedule,
-- Shodan org-task, Caido plugin/API task, Bug Hunting program sync) are being retired in favor of
-- Workflows as the single scheduling mechanism. A workflow created that way is "managed" — locked
-- against edit/delete/status-toggle through the normal Workflows UI/API, since its lifecycle is
-- controlled from its origin page instead (see ManagedWorkflowService).

ALTER TABLE ares.workflow ADD COLUMN locked BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE ares.workflow ADD COLUMN managed_by VARCHAR(50);

CREATE INDEX ix_workflow_managed_by ON ares.workflow(managed_by) WHERE managed_by IS NOT NULL;
