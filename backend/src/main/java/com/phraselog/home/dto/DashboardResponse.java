package com.phraselog.home.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.phraselog.coach.dto.CoachResponse;
import com.phraselog.expression.dto.ExpressionListItem;
import com.phraselog.user.dto.UserResponse;
import java.util.List;

/**
 * Aggregated S04 home payload for {@code GET /home/dashboard} (#54), matching the OpenAPI schema.
 * Single round trip: profile + coach + bookshelf count + 2 recent expressions + due review count +
 * today's roleplay usage. {@code coach} is null when the user has no resolvable selected coach; the
 * client renders the fallback greeting (s04.md edge case).
 */
public record DashboardResponse(
    UserResponse user,
    CoachResponse coach,
    @JsonProperty("bookshelf_count") int bookshelfCount,
    @JsonProperty("recent_expressions") List<ExpressionListItem> recentExpressions,
    @JsonProperty("due_review_count") int dueReviewCount,
    @JsonProperty("today_roleplay_session_count") int todayRoleplaySessionCount,
    @JsonProperty("daily_roleplay_limit") int dailyRoleplayLimit) {}
