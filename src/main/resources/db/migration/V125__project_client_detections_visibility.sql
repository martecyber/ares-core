SET search_path TO ares, public;

-- MSSP users can opt a project into letting its client (CLIENT_USER/CLIENT_ADMIN) accounts
-- see Detections read-only. Off by default — clients see none of a project's raw scan/tool
-- output unless an operator explicitly enables it for that project.
ALTER TABLE project ADD COLUMN clients_can_view_detections BOOLEAN NOT NULL DEFAULT FALSE;
