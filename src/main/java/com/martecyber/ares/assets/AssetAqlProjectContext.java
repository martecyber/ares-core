package com.martecyber.ares.assets;

import java.util.function.Supplier;

/**
 * The one piece of per-request context {@link AssetAqlRegistry}'s {@code scope} field needs that
 * the registry itself — a startup singleton, built once, shared across every request — has no
 * other way to know: which project the current AQL query is scoped to. {@link
 * AssetService#listByAql}/{@link AssetService#countByAql} set this immediately before executing
 * the compiled query (not before, since {@code Specification} predicates — including the
 * correlated EXISTS this backs — only actually run once JPA builds the query inside {@code
 * repository.findAll}/{@code count}, not at {@code compile()} time); the {@code scope} relation's
 * correlation lambda reads it. Mirrors {@code WorkflowSystemAuth.runAs}'s own shape for the same
 * reason: request-scoped state that has to reach a piece of code with no explicit parameter to
 * carry it through, on a thread that may be pooled/reused, so it must always be restored/cleared
 * in a {@code finally}.
 */
public final class AssetAqlProjectContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private AssetAqlProjectContext() {}

    public static <T> T runWithProject(Long projectId, Supplier<T> action) {
        Long previous = CURRENT.get();
        CURRENT.set(projectId);
        try {
            return action.get();
        } finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }

    /** Null outside a project-scoped {@code listByAql}/{@code countByAql} call — e.g. the org-wide
     *  Assets view, or a platform-scoped one — which is exactly when {@code scope.*} can't mean
     *  anything, so {@link AssetAqlRegistry}'s correlation lambda throws a clear {@link
     *  com.martecyber.ares.aql.compile.AqlCompileException} rather than silently matching nothing
     *  (or everything). */
    public static Long currentProjectId() {
        return CURRENT.get();
    }
}
