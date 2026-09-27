-- Backs AQL's new cve.affectedVendor/affectedProduct/affectedVersion fields (JSONB_ARRAY_MATCH,
-- see PostgresSpecificationCompiler#compileJsonbArrayMatch) against CveEntry.affectedProducts —
-- CVE 5.x CNA-schema JSON: [{vendor, product, defaultStatus, versions: [{version, status,
-- lessThan, lessThanOrEqual, versionType}]}]. Never exposed to AQL before this (see
-- CveEntry's own class doc — it was deliberately treated as JSONB "display-only" data).

-- Dotted-numeric version comparator: splits both inputs on '.', compares each segment as an
-- integer when it's purely numeric, falling back to a plain text compare otherwise. Missing
-- trailing segments compare as 0 ("1.2" == "1.2.0"). Covers the overwhelming majority of
-- real-world CVE version strings; genuine semver pre-release/build-metadata ordering (e.g.
-- "1.0.0-alpha" vs "1.0.0-beta") is deliberately out of scope for this first pass — no version
-- comparator of any kind existed in this codebase before this migration.
CREATE OR REPLACE FUNCTION ares.version_compare(a text, b text) RETURNS integer AS $$
DECLARE
    a_parts text[];
    b_parts text[];
    len integer;
    a_seg text;
    b_seg text;
BEGIN
    IF a IS NULL OR b IS NULL THEN RETURN NULL; END IF;
    IF a = b THEN RETURN 0; END IF;
    a_parts := string_to_array(a, '.');
    b_parts := string_to_array(b, '.');
    len := GREATEST(COALESCE(array_length(a_parts, 1), 0), COALESCE(array_length(b_parts, 1), 0));
    FOR i IN 1..len LOOP
        a_seg := COALESCE(a_parts[i], '0');
        b_seg := COALESCE(b_parts[i], '0');
        IF a_seg ~ '^[0-9]+$' AND b_seg ~ '^[0-9]+$' THEN
            IF a_seg::bigint <> b_seg::bigint THEN
                RETURN CASE WHEN a_seg::bigint < b_seg::bigint THEN -1 ELSE 1 END;
            END IF;
        ELSIF a_seg <> b_seg THEN
            RETURN CASE WHEN a_seg < b_seg THEN -1 ELSE 1 END;
        END IF;
    END LOOP;
    RETURN 0;
END;
$$ LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE;

-- True when `needle` falls inside one CveEntry.VersionRange: [start_version, less_than) or
-- [start_version, less_than_or_equal]. `start_version` NULL/blank/"n/a"/"0" means "unbounded
-- below" — CveService#extractAffected already guarantees every persisted range has a real
-- start_version whenever it has neither upper bound (a bound-less, start-less range is dropped
-- entirely at parse time, never reaches storage), so the "exact point version" branch below only
-- ever runs with a real start_version to compare against.
CREATE OR REPLACE FUNCTION ares.cve_version_in_range(needle text, start_version text, less_than text, less_than_or_equal text) RETURNS boolean AS $$
DECLARE
    unbounded_start boolean;
BEGIN
    IF needle IS NULL THEN RETURN false; END IF;
    unbounded_start := start_version IS NULL OR start_version = '' OR lower(start_version) = 'n/a' OR start_version = '0';
    IF NOT unbounded_start AND ares.version_compare(needle, start_version) < 0 THEN
        RETURN false;
    END IF;
    IF less_than IS NOT NULL AND ares.version_compare(needle, less_than) >= 0 THEN
        RETURN false;
    END IF;
    IF less_than_or_equal IS NOT NULL AND ares.version_compare(needle, less_than_or_equal) > 0 THEN
        RETURN false;
    END IF;
    IF less_than IS NULL AND less_than_or_equal IS NULL THEN
        RETURN lower(needle) = lower(start_version); -- exact point version, no range at all
    END IF;
    RETURN true;
END;
$$ LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE;

-- Resolves AQL's `affectedVersion == <needle>`: for each affected-product element, starts from
-- defaultStatus (missing/null treated as "unaffected" — the safer default, since treating it as
-- "affected" would make an incompletely-described product match by default) and applies every
-- versions[] range containing `needle`, in array order — last match wins, mirroring how the
-- upstream CNA JSON is authored (a later, more specific range overrides an earlier, broader one
-- when they overlap; ranges are non-overlapping in the common case, where order doesn't matter).
-- True as soon as ANY product element resolves to "affected".
CREATE OR REPLACE FUNCTION ares.cve_version_affected(affected jsonb, needle text) RETURNS boolean AS $$
DECLARE
    product jsonb;
    range jsonb;
    resolved text;
BEGIN
    IF affected IS NULL OR needle IS NULL THEN RETURN false; END IF;
    FOR product IN SELECT * FROM jsonb_array_elements(affected) LOOP
        resolved := COALESCE(product->>'defaultStatus', 'unaffected');
        FOR range IN SELECT * FROM jsonb_array_elements(COALESCE(product->'versions', '[]'::jsonb)) LOOP
            IF range->>'status' IS NOT NULL
                AND ares.cve_version_in_range(needle, range->>'version', range->>'lessThan', range->>'lessThanOrEqual') THEN
                resolved := range->>'status';
            END IF;
        END LOOP;
        IF lower(resolved) = 'affected' THEN
            RETURN true;
        END IF;
    END LOOP;
    RETURN false;
END;
$$ LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE;

-- Case-insensitive membership check for a jsonb array of objects: does any element have `key`
-- equal to `needle`? Backs affectedVendor/affectedProduct — mirrors ares.array_contains_ci's
-- (V157) case-insensitivity convention for plain text[] columns, applied to a jsonb array instead.
CREATE OR REPLACE FUNCTION ares.jsonb_array_field_contains_ci(arr jsonb, key text, needle text) RETURNS boolean AS $$
    SELECT EXISTS (
        SELECT 1 FROM jsonb_array_elements(COALESCE(arr, '[]'::jsonb)) elem
        WHERE LOWER(elem->>key) = LOWER(needle)
    )
$$ LANGUAGE sql IMMUTABLE PARALLEL SAFE;
