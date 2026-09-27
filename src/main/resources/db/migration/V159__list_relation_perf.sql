-- Deep nested list[X] AQL relations (e.g. exploit -> cves -> cwes -> relatedCapecs -> id) compile to
-- correlated EXISTS subqueries per hop, using ares.array_contains_ci (V157) as the correlation —
-- that predicate is an opaque function call from the planner's point of view, so it can NEVER use
-- the GIN indexes already sitting on these array columns (confirmed via EXPLAIN ANALYZE: a 2-hop
-- query against 378k CVEs took ~11s doing a full Seq Scan, even with enable_seqscan=off — the
-- planner genuinely has no alternative plan for this correlation shape, it isn't a costing issue).
--
-- The fix (PostgresSpecificationCompiler#compileRelation, listOf branch) restructures a listOf
-- relation's correlation entirely: instead of a per-outer-row correlated EXISTS, it precomputes the
-- (usually small, leaf-filtered) set of matching target ids ONCE via an uncorrelated array_agg
-- subquery, then checks the source array for overlap against that fixed set — turning the predicate
-- into "indexed_col && small_constant_array", which the planner CAN push down to a GIN Bitmap Index
-- Scan. Measured: the same 2-hop query dropped from ~11s to ~350ms once both sides of the overlap
-- are expressed as ares.lower_array(...) — an inlinable SQL function, so the planner still sees the
-- indexed expression directly, not a black box.

CREATE OR REPLACE FUNCTION ares.lower_array(arr text[]) RETURNS text[] AS $$
    SELECT ARRAY(SELECT lower(elem) FROM unnest(arr) AS elem)
$$ LANGUAGE sql IMMUTABLE PARALLEL SAFE;

-- candidates is always pre-lowered by the array_agg(lower(...)) subquery that produces it — arr is
-- lowered here so the expression matches the GIN indexes below verbatim.
CREATE OR REPLACE FUNCTION ares.array_overlaps_ci(arr text[], candidates text[]) RETURNS boolean AS $$
    SELECT ares.lower_array(arr) && candidates
$$ LANGUAGE sql IMMUTABLE PARALLEL SAFE;

-- One GIN index per text[] column used as the "arrayAttribute" side of a RelationAqlField.listOf
-- relation (see CveAqlRegistry/CweAqlRegistry/CapecAqlRegistry/OwaspAqlRegistry/
-- CveKevDetailAqlRegistry/ExploitAqlRegistry) — every one of these is a candidate outer/source side
-- of a list[X] relation, so every one benefits the same way cve.cwes did in the measurement above.
CREATE INDEX ix_cve_cwes_lower ON ares.cve USING GIN (ares.lower_array(cwes));
CREATE INDEX ix_cwe_parent_ids_lower ON ares.cwe USING GIN (ares.lower_array(parent_ids));
CREATE INDEX ix_cwe_child_ids_lower ON ares.cwe USING GIN (ares.lower_array(child_ids));
CREATE INDEX ix_cwe_related_capec_ids_lower ON ares.cwe USING GIN (ares.lower_array(related_capec_ids));
CREATE INDEX ix_capec_related_cwe_ids_lower ON ares.capec USING GIN (ares.lower_array(related_cwe_ids));
CREATE INDEX ix_capec_related_attack_technique_ids_lower ON ares.capec USING GIN (ares.lower_array(related_attack_technique_ids));
CREATE INDEX ix_capec_parent_capec_ids_lower ON ares.capec USING GIN (ares.lower_array(parent_capec_ids));
CREATE INDEX ix_capec_child_capec_ids_lower ON ares.capec USING GIN (ares.lower_array(child_capec_ids));
CREATE INDEX ix_owasp_cwes_lower ON ares.owasp USING GIN (ares.lower_array(cwes));
CREATE INDEX ix_cve_kev_detail_cwes_lower ON ares.cve_kev_detail USING GIN (ares.lower_array(cwes));
CREATE INDEX ix_exploit_cve_ids_lower ON ares.exploit USING GIN (ares.lower_array(cve_ids));
