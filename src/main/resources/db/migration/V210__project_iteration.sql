-- Persisted iteration records for MONITOR projects: allows editing actual
-- start/end dates and tracks the "sliding" behavior when an operator doesn't
-- approve the advance on the planned end date.
CREATE TABLE ares.project_iteration (
    id              BIGSERIAL PRIMARY KEY,
    project_id      BIGINT       NOT NULL REFERENCES ares.project(id) ON DELETE CASCADE,
    label           VARCHAR(10)  NOT NULL,
    planned_start   DATE         NOT NULL,
    planned_end     DATE         NOT NULL,
    actual_start    DATE,
    actual_end      DATE,
    approved_at     TIMESTAMPTZ,
    UNIQUE (project_id, label)
);
CREATE INDEX idx_project_iteration_project_start ON ares.project_iteration(project_id, planned_start);

-- Backfill: for every MONITOR project that already has detection iteration stats,
-- create a row per distinct iteration label with planned dates derived from the
-- project's cadence.  Because we can't call Java's MonitorIterationHelper from SQL
-- we reconstruct boundaries inline for each cadence type.
--
-- The backfill covers all labels that appear in detection_iteration_stat for a
-- project. For historical iterations we set actual_start = planned_start,
-- actual_end = planned_end (assuming they ran on schedule).
-- The *current* active iteration (matching project.active_iteration_label) gets
-- actual_start = planned_start but actual_end = NULL (still open).
INSERT INTO ares.project_iteration (project_id, label, planned_start, planned_end, actual_start, actual_end)
SELECT DISTINCT
    p.id,
    dis.iteration_label,
    -- planned_start: reconstruct from the label format
    CASE p.iteration_cadence
        WHEN 'weekly' THEN
            -- label "YY-Www" → ISO week start (Monday)
            date_trunc('week', make_date(2000 + CAST(left(dis.iteration_label, 2) AS int),
                1, 1) + ((CAST(substring(dis.iteration_label FROM 5) AS int) - 1) * 7 || ' days')::interval)::date
        WHEN 'biweekly' THEN
            date_trunc('week', make_date(2000 + CAST(left(dis.iteration_label, 2) AS int),
                1, 1) + (((CAST(substring(dis.iteration_label FROM 6) AS int) - 1) * 14) || ' days')::interval)::date
        WHEN 'monthly' THEN
            make_date(2000 + CAST(left(dis.iteration_label, 2) AS int),
                CAST(right(dis.iteration_label, 2) AS int), 1)
        WHEN 'quarterly' THEN
            make_date(2000 + CAST(left(dis.iteration_label, 2) AS int),
                (CAST(substring(dis.iteration_label FROM 5) AS int) - 1) * 3 + 1, 1)
        WHEN 'semiannual' THEN
            make_date(2000 + CAST(left(dis.iteration_label, 2) AS int),
                CASE WHEN substring(dis.iteration_label FROM 5) = '1' THEN 1 ELSE 7 END, 1)
        WHEN 'annual' THEN
            make_date(2000 + CAST(dis.iteration_label AS int), 1, 1)
        ELSE CURRENT_DATE
    END,
    -- planned_end: planned_start + cadence period
    CASE p.iteration_cadence
        WHEN 'weekly' THEN
            (date_trunc('week', make_date(2000 + CAST(left(dis.iteration_label, 2) AS int),
                1, 1) + ((CAST(substring(dis.iteration_label FROM 5) AS int) - 1) * 7 || ' days')::interval)::date + 7)
        WHEN 'biweekly' THEN
            (date_trunc('week', make_date(2000 + CAST(left(dis.iteration_label, 2) AS int),
                1, 1) + (((CAST(substring(dis.iteration_label FROM 6) AS int) - 1) * 14) || ' days')::interval)::date + 14)
        WHEN 'monthly' THEN
            (make_date(2000 + CAST(left(dis.iteration_label, 2) AS int),
                CAST(right(dis.iteration_label, 2) AS int), 1) + interval '1 month')::date
        WHEN 'quarterly' THEN
            (make_date(2000 + CAST(left(dis.iteration_label, 2) AS int),
                (CAST(substring(dis.iteration_label FROM 5) AS int) - 1) * 3 + 1, 1) + interval '3 months')::date
        WHEN 'semiannual' THEN
            (make_date(2000 + CAST(left(dis.iteration_label, 2) AS int),
                CASE WHEN substring(dis.iteration_label FROM 5) = '1' THEN 1 ELSE 7 END, 1) + interval '6 months')::date
        WHEN 'annual' THEN
            make_date(2000 + CAST(dis.iteration_label AS int) + 1, 1, 1)
        ELSE CURRENT_DATE
    END,
    -- actual_start = planned_start for all backfilled rows
    CASE p.iteration_cadence
        WHEN 'weekly' THEN
            date_trunc('week', make_date(2000 + CAST(left(dis.iteration_label, 2) AS int),
                1, 1) + ((CAST(substring(dis.iteration_label FROM 5) AS int) - 1) * 7 || ' days')::interval)::date
        WHEN 'biweekly' THEN
            date_trunc('week', make_date(2000 + CAST(left(dis.iteration_label, 2) AS int),
                1, 1) + (((CAST(substring(dis.iteration_label FROM 6) AS int) - 1) * 14) || ' days')::interval)::date
        WHEN 'monthly' THEN
            make_date(2000 + CAST(left(dis.iteration_label, 2) AS int),
                CAST(right(dis.iteration_label, 2) AS int), 1)
        WHEN 'quarterly' THEN
            make_date(2000 + CAST(left(dis.iteration_label, 2) AS int),
                (CAST(substring(dis.iteration_label FROM 5) AS int) - 1) * 3 + 1, 1)
        WHEN 'semiannual' THEN
            make_date(2000 + CAST(left(dis.iteration_label, 2) AS int),
                CASE WHEN substring(dis.iteration_label FROM 5) = '1' THEN 1 ELSE 7 END, 1)
        WHEN 'annual' THEN
            make_date(2000 + CAST(dis.iteration_label AS int), 1, 1)
        ELSE CURRENT_DATE
    END,
    -- actual_end: NULL if this is the currently active iteration, otherwise planned_end
    CASE WHEN dis.iteration_label = p.active_iteration_label THEN NULL
    ELSE
        CASE p.iteration_cadence
            WHEN 'weekly' THEN
                (date_trunc('week', make_date(2000 + CAST(left(dis.iteration_label, 2) AS int),
                    1, 1) + ((CAST(substring(dis.iteration_label FROM 5) AS int) - 1) * 7 || ' days')::interval)::date + 7)
            WHEN 'biweekly' THEN
                (date_trunc('week', make_date(2000 + CAST(left(dis.iteration_label, 2) AS int),
                    1, 1) + (((CAST(substring(dis.iteration_label FROM 6) AS int) - 1) * 14) || ' days')::interval)::date + 14)
            WHEN 'monthly' THEN
                (make_date(2000 + CAST(left(dis.iteration_label, 2) AS int),
                    CAST(right(dis.iteration_label, 2) AS int), 1) + interval '1 month')::date
            WHEN 'quarterly' THEN
                (make_date(2000 + CAST(left(dis.iteration_label, 2) AS int),
                    (CAST(substring(dis.iteration_label FROM 5) AS int) - 1) * 3 + 1, 1) + interval '3 months')::date
            WHEN 'semiannual' THEN
                (make_date(2000 + CAST(left(dis.iteration_label, 2) AS int),
                    CASE WHEN substring(dis.iteration_label FROM 5) = '1' THEN 1 ELSE 7 END, 1) + interval '6 months')::date
            WHEN 'annual' THEN
                make_date(2000 + CAST(dis.iteration_label AS int) + 1, 1, 1)
            ELSE CURRENT_DATE
        END
    END
FROM ares.project p
JOIN (SELECT DISTINCT project_id, iteration_label FROM ares.detection_iteration_stat) dis
    ON dis.project_id = p.id
WHERE p.iteration_cadence IS NOT NULL
ON CONFLICT (project_id, label) DO NOTHING;
