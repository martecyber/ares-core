ALTER TABLE ares.job ADD COLUMN IF NOT EXISTS project_id BIGINT REFERENCES ares.project(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_job_project ON ares.job(project_id);
