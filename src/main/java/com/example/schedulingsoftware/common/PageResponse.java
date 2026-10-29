package com.example.schedulingsoftware.common;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * One page of a list, with what the screen needs to draw page links.
 *
 * @param content       the items on this page
 * @param page          page number, starting at 0
 * @param size          items per page
 * @param totalElements items across all pages
 * @param totalPages    number of pages (0 when there are no items)
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    /** Largest page a client may ask for. */
    public static final int MAX_SIZE = 100;
    public static final int DEFAULT_SIZE = 25;

    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    /** A page request with the number and size kept in range (negative page → 0, size 1–100). */
    public static PageRequest request(Integer page, Integer size, Sort sort) {
        int p = page == null ? 0 : Math.max(0, page);
        int s = size == null ? DEFAULT_SIZE : Math.clamp(size, 1, MAX_SIZE);
        return PageRequest.of(p, s, sort);
    }
}
