package com.phraselog.expression.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Paginated list body for {@code GET /expressions} (#45). {@code nextCursor} is null on the last
 * page.
 */
public record ExpressionListResponse(
    List<ExpressionListItem> items, @JsonProperty("next_cursor") String nextCursor) {}
