-- V162: adds a platform-wide tag tier on top of the existing per-organization catalog (V117).
-- organization_id NULL means a platform tag — manageable only by MSSP_ADMIN, visible/assignable
-- everywhere (both org-scoped entities like Asset/Detection/Finding, and platform-wide catalog
-- entities like Exploit/FindingTemplate, which have no organization to match an org-scoped tag
-- against at all).
SET search_path TO ares, public;

ALTER TABLE tag ALTER COLUMN organization_id DROP NOT NULL;

-- Postgres treats every NULL as distinct under a plain UNIQUE(organization_id, name), so that
-- existing constraint alone would silently allow duplicate-named platform tags — this partial
-- index is what actually enforces "unique name among platform tags", case-insensitively (the
-- org-scoped constraint stays case-sensitive at the DB level as before; TagService already
-- enforces case-insensitivity for org tags in application code, and will do the same here).
CREATE UNIQUE INDEX ux_tag_platform_name ON tag (LOWER(name)) WHERE organization_id IS NULL;
