package com.thedogs.common;

import java.util.List;

/**
 * Cursor-based pagination wrapper returned by list endpoints.
 *
 * @param data the page of items
 * @param nextCursor opaque cursor for the next page; null when no more pages
 * @param total total count of matching records (optional, may be null for performance)
 */
public record PageResponse<T>(List<T> data, String nextCursor, Long total) {

  public static <T> PageResponse<T> of(List<T> data, String nextCursor) {
    return new PageResponse<>(data, nextCursor, null);
  }

  public static <T> PageResponse<T> of(List<T> data, String nextCursor, long total) {
    return new PageResponse<>(data, nextCursor, total);
  }
}
