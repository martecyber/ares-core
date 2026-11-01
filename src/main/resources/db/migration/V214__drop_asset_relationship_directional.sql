-- Every relationship was written with directional = TRUE and nothing ever read the flag:
-- direction is defined by from/to and the link type. Drop the vestigial column.
ALTER TABLE ares.asset_relationships DROP COLUMN IF EXISTS directional;
