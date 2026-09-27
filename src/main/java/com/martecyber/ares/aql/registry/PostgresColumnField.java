package com.martecyber.ares.aql.registry;

import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;

/** An {@link AqlField} the Postgres compiler can resolve to a JPA {@link Path} — PHYSICAL_COLUMN or VIRTUAL kinds. */
public interface PostgresColumnField<T> extends AqlField<T> {
    Path<?> resolvePath(Root<T> root);
}
