ALTER TABLE ares."user"
    ADD COLUMN avatar_data BYTEA,
    ADD COLUMN avatar_mime VARCHAR(50);
