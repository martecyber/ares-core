-- Advisory-only counterpart to agent.capabilities: which tools AgentToolSpecRegistry knows
-- about that this agent currently CAN'T run, and why (missing binary, version too old, needs
-- root, customBuilder module not synced / blocked by local policy...). Never consulted by task
-- dispatch (that still reads only capabilities) — purely for AgentDetailView to explain an
-- absence instead of leaving it silent.
ALTER TABLE ares.agent
    ADD COLUMN unavailable_tools JSONB NOT NULL DEFAULT '[]';
