SET search_path TO ares, public;

-- V120 created `id` as SMALLINT; the JPA entity maps it as Integer (Postgres `integer`),
-- which fails Hibernate's schema validation on startup. Widen it to match.
ALTER TABLE kb_vulncheck_settings ALTER COLUMN id TYPE INTEGER;
