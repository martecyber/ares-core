package com.martecyber.ares.common;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Envelope for paginated list responses. Cursor-based pagination is coming;
 * for now we use page/size so we can get UI plumbing working.
 */
public record PagedResponse<T>(
    List<T> items,
    int page,
    int size,
    long total,
    int totalPages,
    boolean hasMore
) {
    public static <E, T> PagedResponse<T> of(Page<E> src, Function<E, T> mapper) {
        return new PagedResponse<>(
            src.getContent().stream().map(mapper).toList(),
            src.getNumber(),
            src.getSize(),
            src.getTotalElements(),
            src.getTotalPages(),
            src.hasNext()
        );
    }

    public static <T> PagedResponse<T> of(Page<T> src) {
        return new PagedResponse<>(
            src.getContent(),
            src.getNumber(),
            src.getSize(),
            src.getTotalElements(),
            src.getTotalPages(),
            src.hasNext()
        );
    }
}
