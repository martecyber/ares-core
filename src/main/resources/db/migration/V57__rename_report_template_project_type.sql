SET search_path TO ares, public;

ALTER TABLE report_template_engagement_type RENAME TO report_template_project_type;
ALTER TABLE report_template_project_type    RENAME COLUMN engagement_type_id TO project_type_id;
ALTER TABLE report_template_project_type    RENAME CONSTRAINT report_template_engagement_type_pkey TO report_template_project_type_pkey;
