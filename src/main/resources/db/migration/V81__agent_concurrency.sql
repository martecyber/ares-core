-- Number of agent_task rows an agent is allowed to execute concurrently.
-- Default 1 keeps the historical one-at-a-time behaviour for existing agents.
-- Used by the per-pool timeline view to render N rows ("slots") per agent.
-- The dispatcher itself doesn't enforce this yet — claim() still hands out
-- one task at a time per heartbeat — so right now this is a presentation /
-- capacity-planning hint, not a hard runtime guarantee.
ALTER TABLE ares.agent
    ADD COLUMN max_concurrent_tasks INT NOT NULL DEFAULT 1
    CHECK (max_concurrent_tasks >= 1);
