-- Replace DB-stored status with computed status derived from dates + explicit completion
-- Status logic (computed in application layer):
--   completed  → completed_at IS NOT NULL
--   scheduled  → completed_at IS NULL AND (start_date IS NULL OR start_date > NOW())
--   active     → completed_at IS NULL AND start_date <= NOW() AND (end_date IS NULL OR end_date >= NOW())
--   past_due   → completed_at IS NULL AND end_date IS NOT NULL AND end_date < NOW()

ALTER TABLE ares.engagement
    DROP COLUMN IF EXISTS status_id,
    ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_engagement_computed_status
    ON ares.engagement (completed_at, start_date, end_date);
