package com.martecyber.ares.aql.registry;

import com.martecyber.ares.aql.parser.AqlOperator;

import java.util.List;
import java.util.Set;

/**
 * A boolean membership/match check against a jsonb ARRAY column, delegated to a named Postgres
 * function rather than expressed as a plain {@link JsonbPathField} scalar extraction — needed
 * whenever the match has to iterate the array's elements and apply logic a single {@code
 * jsonb_extract_path_text} call can't (e.g. {@code CveEntry.affectedProducts}: "does any element's
 * vendor/product match", or "does the queried version fall inside any element's affected version
 * ranges" — see {@code CveAqlRegistry}'s {@code affectedVendor}/{@code affectedProduct}/{@code
 * affectedVersion} and the SQL functions backing them, V185).
 *
 * <p>Only ever EQ/NEQ (see {@link #supportedOperators()}) — there's no meaningful GT/LT/CONTAINS
 * over "some array element matches," and HAS is reserved for {@link ArrayAqlField}'s native
 * Postgres {@code text[]} columns, not jsonb ones. Same "independent per comparison" caveat every
 * other RELATION/KB_MATERIALIZED field in this codebase already documents: ANDing {@code
 * affectedVendor == X AND affectedProduct == Y AND affectedVersion == Z} in one AQL query means
 * "some array element satisfies each condition, possibly a different element per condition," not
 * "one single element satisfies all three" — there's no correlated-per-element AND machinery in
 * this compiler for jsonb arrays (nor for the RELATION kind's own 1:many case), so this stays
 * consistent with that existing, already-accepted limitation rather than inventing new machinery
 * unique to this one field kind.
 *
 * @param jsonbColumn the JPA attribute name of the jsonb array column on the owning entity
 * @param sqlFunction schema-qualified Postgres function name, signature {@code (jsonb, extraArgs...,
 *                    text needle) RETURNS boolean}
 * @param extraArgs   literal string arguments passed to {@code sqlFunction} between the jsonb
 *                    column and the AQL comparison value (e.g. the jsonb object key to match on)
 *                    — empty when the function only needs the column and the AQL value itself
 */
public record JsonbArrayMatchField<T>(
    String name,
    String jsonbColumn,
    String sqlFunction,
    List<String> extraArgs
) implements AqlField<T> {

    @Override
    public AqlFieldType type() {
        return AqlFieldType.STRING;
    }

    @Override
    public AqlFieldKind kind() {
        return AqlFieldKind.JSONB_ARRAY_MATCH;
    }

    @Override
    public Set<AqlOperator> supportedOperators() {
        return Set.of(AqlOperator.EQ, AqlOperator.NEQ);
    }
}
