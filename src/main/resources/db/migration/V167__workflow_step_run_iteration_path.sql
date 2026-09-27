-- LOOP nodes now run a real graph subgraph once per item instead of an embedded block list, so a
-- body node can execute more than once per workflow run (once per iteration, sequentially). This
-- column disambiguates repeated rows for the same node_id within one run: an ordered JSON array of
-- {"loop": "<loopNodeId>", "i": <index>} objects, outer-to-inner, empty for anything never inside
-- a loop. No unique constraint exists on (workflow_run_id, node_id) today, so nothing to relax.
ALTER TABLE ares.workflow_step_run ADD COLUMN iteration_path JSONB NOT NULL DEFAULT '[]';
