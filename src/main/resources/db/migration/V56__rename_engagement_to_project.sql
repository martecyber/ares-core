SET search_path TO ares, public;

-- ============================================================
-- Rename tables
-- ============================================================
ALTER TABLE engagement                     RENAME TO project;
ALTER TABLE engagement_type                RENAME TO project_type;
ALTER TABLE engagement_status              RENAME TO project_status;
ALTER TABLE engagement_status_transition   RENAME TO project_status_transition;
ALTER TABLE engagement_member              RENAME TO project_member;
ALTER TABLE engagement_scope_entry         RENAME TO project_scope_entry;
ALTER TABLE engagement_status_history      RENAME TO project_status_history;
ALTER TABLE engagement_asset_access        RENAME TO project_asset_access;
ALTER TABLE engagement_bug_hunting_program RENAME TO project_bug_hunting_program;

-- ============================================================
-- Rename engagement_id columns to project_id
-- ============================================================
ALTER TABLE project_member              RENAME COLUMN engagement_id TO project_id;
ALTER TABLE project_scope_entry         RENAME COLUMN engagement_id TO project_id;
ALTER TABLE project_status_history      RENAME COLUMN engagement_id TO project_id;
ALTER TABLE project_asset_access        RENAME COLUMN engagement_id TO project_id;
ALTER TABLE project_bug_hunting_program RENAME COLUMN engagement_id TO project_id;
ALTER TABLE finding                     RENAME COLUMN engagement_id TO project_id;
ALTER TABLE detection                   RENAME COLUMN engagement_id TO project_id;
ALTER TABLE file                        RENAME COLUMN engagement_id TO project_id;
ALTER TABLE report                      RENAME COLUMN engagement_id TO project_id;
ALTER TABLE scan_import                 RENAME COLUMN engagement_id TO project_id;
ALTER TABLE integration_grant           RENAME COLUMN engagement_id TO project_id;
ALTER TABLE integration_schedule        RENAME COLUMN engagement_id TO project_id;

-- ============================================================
-- Rename indexes
-- ============================================================
ALTER INDEX IF EXISTS ix_engagement_org              RENAME TO ix_project_org;
ALTER INDEX IF EXISTS ix_engagement_status           RENAME TO ix_project_status;
ALTER INDEX IF EXISTS ix_ese_engagement              RENAME TO ix_ese_project;
ALTER INDEX IF EXISTS ix_finding_engagement          RENAME TO ix_finding_project;
ALTER INDEX IF EXISTS ix_detection_engagement        RENAME TO ix_detection_project;
ALTER INDEX IF EXISTS ix_file_engagement             RENAME TO ix_file_project;
ALTER INDEX IF EXISTS ix_report_engagement           RENAME TO ix_report_project;
ALTER INDEX IF EXISTS idx_engagement_computed_status RENAME TO idx_project_computed_status;
ALTER INDEX IF EXISTS idx_scan_import_engagement     RENAME TO idx_scan_import_project;
ALTER INDEX IF EXISTS ix_integration_grant_engagement RENAME TO ix_integration_grant_project;
ALTER INDEX IF EXISTS ix_integration_schedule_eng    RENAME TO ix_integration_schedule_proj;
ALTER INDEX IF EXISTS idx_detection_dedup            RENAME TO idx_detection_dedup_project;
ALTER INDEX IF EXISTS idx_detection_plugin           RENAME TO idx_detection_plugin_project;

-- ============================================================
-- Rename primary key constraints
-- ============================================================
ALTER TABLE project                   RENAME CONSTRAINT engagement_pkey TO project_pkey;
ALTER TABLE project_type              RENAME CONSTRAINT engagement_type_pkey TO project_type_pkey;
ALTER TABLE project_status            RENAME CONSTRAINT engagement_status_pkey TO project_status_pkey;
ALTER TABLE project_member            RENAME CONSTRAINT engagement_member_pkey TO project_member_pkey;
ALTER TABLE project_scope_entry       RENAME CONSTRAINT engagement_scope_entry_pkey TO project_scope_entry_pkey;
ALTER TABLE project_status_history    RENAME CONSTRAINT engagement_status_history_pkey TO project_status_history_pkey;
ALTER TABLE project_asset_access      RENAME CONSTRAINT engagement_asset_access_pkey TO project_asset_access_pkey;
ALTER TABLE project_bug_hunting_program RENAME CONSTRAINT engagement_bug_hunting_program_pkey TO project_bug_hunting_program_pkey;

-- ============================================================
-- Update permission codes
-- ============================================================
UPDATE permission SET code = 'PROJECT_READ',    description = 'View projects'             WHERE code = 'ENGAGEMENT_READ';
UPDATE permission SET code = 'PROJECT_WRITE',   description = 'Create and update projects' WHERE code = 'ENGAGEMENT_WRITE';
UPDATE permission SET code = 'PROJECT_DELETE',  description = 'Delete projects'            WHERE code = 'ENGAGEMENT_DELETE';
UPDATE permission SET code = 'PROJECT_LEAD',    description = 'Can be assigned as leader of a project'   WHERE code = 'ENGAGEMENT_LEAD';
UPDATE permission SET code = 'PROJECT_OPERATE', description = 'Can be assigned as operator of a project' WHERE code = 'ENGAGEMENT_OPERATE';
