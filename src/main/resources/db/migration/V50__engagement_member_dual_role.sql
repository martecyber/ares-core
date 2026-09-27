-- Allow a user to hold both 'lead' and 'operator' roles in the same engagement.
-- The previous PK (engagement_id, user_id) only allowed one row per user.
-- The new PK (engagement_id, user_id, role) allows one row per user per role.

UPDATE ares.engagement_member SET role = 'operator' WHERE role IS NULL;
ALTER TABLE ares.engagement_member ALTER COLUMN role SET NOT NULL;
ALTER TABLE ares.engagement_member DROP CONSTRAINT engagement_member_pkey;
ALTER TABLE ares.engagement_member ADD PRIMARY KEY (engagement_id, user_id, role);
