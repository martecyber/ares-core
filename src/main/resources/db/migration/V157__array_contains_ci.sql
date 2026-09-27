-- Case-insensitive array-membership helper for AQL's HAS operator (see
-- PostgresSpecificationCompiler#arrayContainsPredicate). Every other HAS-queryable text[] column
-- in this codebase is lowercase-normalized at write time, so the old array_position(arr, lower(v))
-- IS NOT NULL check worked fine — but attack_technique.platforms/data_sources/permissions_required
-- (and tactics) deliberately keep their ORIGINAL display case (ares-ui renders them directly as
-- badges), so that same check would silently require exact-case queries for exactly those columns,
-- breaking AQL's "every string comparison is case-insensitive" invariant. This works regardless of
-- the array's stored case, so PostgresSpecificationCompiler can use it universally instead of
-- special-casing which columns need which comparison.
CREATE OR REPLACE FUNCTION ares.array_contains_ci(arr text[], needle text) RETURNS boolean AS $$
    SELECT EXISTS (SELECT 1 FROM unnest(arr) AS elem WHERE LOWER(elem) = LOWER(needle))
$$ LANGUAGE sql IMMUTABLE PARALLEL SAFE;
