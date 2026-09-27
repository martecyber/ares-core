-- AQL implementation plan, Phase 0: introduce the canonical P0-P4 priority scale as a real
-- stored column, replacing severity as the source of truth (severity strings are now derived
-- from priority at write time — see PriorityThresholds.java / SeverityThresholds.java). 0=P0
-- (critical/most urgent) .. 4=P4 (info/least urgent), matching the P0-P4 label convention
-- already seeded in V129__priority_colors.sql.
--
-- detection.priority is NOT NULL, backfilled from severity (unrecognized values -> 4, matching
-- the ELSE->0(lowest weight) fallback severityWeight's @Formula already uses).
--
-- finding.priority stays nullable, mirroring finding.severity's existing nullable state
-- (V127__priorization.sql) — no default score yet means no priority, shown as "P?" client-side,
-- same as today's severity.

ALTER TABLE ares.detection ADD COLUMN priority SMALLINT;

UPDATE ares.detection SET priority = CASE severity
    WHEN 'critical' THEN 0
    WHEN 'high'     THEN 1
    WHEN 'medium'   THEN 2
    WHEN 'low'      THEN 3
    WHEN 'info'     THEN 4
    ELSE 4
END;

ALTER TABLE ares.detection ALTER COLUMN priority SET NOT NULL;
CREATE INDEX ix_detection_priority ON ares.detection(priority);

ALTER TABLE ares.finding ADD COLUMN priority SMALLINT;

UPDATE ares.finding SET priority = CASE severity
    WHEN 'critical' THEN 0
    WHEN 'high'     THEN 1
    WHEN 'medium'   THEN 2
    WHEN 'low'      THEN 3
    WHEN 'info'     THEN 4
    ELSE NULL
END;

CREATE INDEX ix_finding_priority ON ares.finding(priority);
