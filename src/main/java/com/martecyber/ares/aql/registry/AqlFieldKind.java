package com.martecyber.ares.aql.registry;

/**
 * How a field is resolved at query time. Compilers dispatch on this — a field's kind determines
 * which store(s) it touches and whether the federated fallback is involved. See the AQL
 * implementation plan for the full rationale.
 */
public enum AqlFieldKind {
    /** A real JPA-mapped column. */
    PHYSICAL_COLUMN,
    /** Computed, not a stored column (e.g. a Hibernate {@code @Formula}). */
    VIRTUAL,
    /** A dynamic/custom field inside a typed jsonb column, validated against field_definition. */
    JSONB_PATH,
    /** A hot Knowledge Base field mirrored onto Postgres (kb_materialized_ref) — no Mongo round trip. */
    KB_MATERIALIZED,
    /** A native Postgres array column (e.g. {@code text[]}) — fixed-schema scalar-code lists like
     *  {@code cve.cwes}, not the dynamic per-org data JSONB_PATH covers. Only ever meaningfully
     *  supports {@link com.martecyber.ares.aql.parser.AqlOperator#HAS} (membership); EQ/NEQ
     *  against "the whole array equals X" is deliberately left unsupported to keep the surface
     *  unambiguous. See {@link ArrayAqlField}. */
    ARRAY_COLUMN,
    /** A field reached by joining to a related Postgres entity, one or more hops away, correlated
     *  back to the outer entity via a subquery — same store both sides, always. See {@link
     *  RelationAqlField}/{@link RelationLeafAqlField}. */
    RELATION,
    /** A virtual DATE field answering "when did this entity FIRST transition into status X" —
     *  {@code status.<name>}, e.g. {@code status.affected < 2026-06-01}. Compiles to a correlated
     *  scalar subquery ({@code MIN(changed_at)} against that entity's own status-history table,
     *  filtered to rows matching status X) rather than an EXISTS, so unlike {@link #RELATION} it's
     *  directly comparable with the usual DATE operators — see {@link StatusTransitionAqlField}. */
    STATUS_TRANSITION,
    /** A boolean membership/match check against a jsonb ARRAY column, computed by a named Postgres
     *  function — the escape hatch for jsonb-array logic too involved for a plain
     *  jsonb_extract_path_text scalar (see {@link #JSONB_PATH}): iterating the array's elements,
     *  matching more than one sub-field within the same element, or genuine value-range logic
     *  (e.g. CveEntry.affectedProducts' {@code affectedVersion}, which resolves a queried version
     *  against each element's version ranges rather than comparing a single extracted scalar). See
     *  {@link JsonbArrayMatchField}. */
    JSONB_ARRAY_MATCH
}
