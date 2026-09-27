-- V63 declared token_hash as CHAR(64) (PostgreSQL bpchar). The JPA entity maps
-- String + length=64 to VARCHAR(64), so Hibernate's schema-validation rejects the column.
-- Convert in place; the value is a SHA-256 hex string, length is identical either way.

ALTER TABLE ares.agent ALTER COLUMN token_hash TYPE VARCHAR(64);
