-- Lets a project hide an asset relationship from its graph view without touching the
-- underlying (org-wide) asset_relationships row — mirrors what project_asset_access does
-- for assets: presence in this table means "not visible in this project's graph".
-- Org-level relationship deletion (a real, permanent removal of the relationship for
-- every project) is a separate, existing action and is unaffected by this table.
CREATE TABLE ares.project_relationship_hidden (
    project_id    bigint       NOT NULL REFERENCES ares.project(id) ON DELETE CASCADE,
    from_asset_id bigint       NOT NULL,
    to_asset_id   bigint       NOT NULL,
    type          varchar(255) NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (project_id, from_asset_id, to_asset_id, type)
);
