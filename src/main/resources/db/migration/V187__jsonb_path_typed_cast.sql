-- Fixes a real, previously-unexercised bug in PostgresSpecificationCompiler#compileJsonbPath:
-- jsonb_extract_path_text always returns text, and JPA Criteria's Expression.as(Double.class) on
-- a cb.function() result does NOT emit an actual SQL CAST — Hibernate just tags the Java-side type,
-- so Postgres receives a raw text column compared against a double-precision-typed bind parameter
-- and fails with "operator does not exist: text = double precision". Every field_definition row
-- registered before V186 (asset.metadata.interfaceType) was data_type='enum' (a STRING comparison,
-- which never hits this path), so the bug had no field to surface on until service.port
-- (data_type='number') was added.
--
-- These wrap the extraction AND the cast in one server-side function per target type, called
-- directly from compileJsonbPath instead of relying on Criteria's .as(...) — mirrors this
-- migration series' existing "small ares.* SQL helper function" pattern (array_contains_ci,
-- cve_version_affected, ...). PL/pgSQL with EXCEPTION WHEN OTHERS (not a plain SQL function): the
-- jsonb column is free-form, unvalidated data written by many independent importers, so a stored
-- value that doesn't actually match its field_definition's declared data_type (e.g. a stray
-- non-numeric string under a NUMBER-typed key) must make the comparison simply not match, not
-- 500 the whole query.
CREATE OR REPLACE FUNCTION ares.jsonb_path_double(doc jsonb, key text) RETURNS double precision AS $$
DECLARE
    raw text;
BEGIN
    raw := jsonb_extract_path_text(doc, key);
    IF raw IS NULL OR raw = '' THEN RETURN NULL; END IF;
    RETURN raw::double precision;
EXCEPTION WHEN OTHERS THEN RETURN NULL;
END;
$$ LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE;

CREATE OR REPLACE FUNCTION ares.jsonb_path_timestamptz(doc jsonb, key text) RETURNS timestamptz AS $$
DECLARE
    raw text;
BEGIN
    raw := jsonb_extract_path_text(doc, key);
    IF raw IS NULL OR raw = '' THEN RETURN NULL; END IF;
    RETURN raw::timestamptz;
EXCEPTION WHEN OTHERS THEN RETURN NULL;
END;
$$ LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE;

CREATE OR REPLACE FUNCTION ares.jsonb_path_boolean(doc jsonb, key text) RETURNS boolean AS $$
DECLARE
    raw text;
BEGIN
    raw := jsonb_extract_path_text(doc, key);
    IF raw IS NULL OR raw = '' THEN RETURN NULL; END IF;
    RETURN raw::boolean;
EXCEPTION WHEN OTHERS THEN RETURN NULL;
END;
$$ LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE;
