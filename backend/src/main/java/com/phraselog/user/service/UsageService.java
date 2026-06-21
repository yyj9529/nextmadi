package com.phraselog.user.service;

import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.ApiErrorException;
import com.phraselog.user.dto.UsageTodayResponse;
import com.phraselog.user.repository.UsageRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Backend core for {@code GET /usage/today} (#52).
 *
 * <p>"Today" is the UTC calendar day, evaluated as a half-open range {@code [startOfDay,
 * startOfNextDay)} on the timestamptz columns. Timestamps are stored UTC (data-model.md), so this
 * is equivalent to the data-model's {@code started_at::date = current_date} when the server runs
 * UTC, but is deterministic (driven by an injected {@link Clock}) and index-friendly. The {@link
 * Clock} also makes the midnight boundary unit-testable.
 */
@Service
public class UsageService {

  private static final int DAILY_ROLEPLAY_LIMIT = 2;
  private static final Integer ANALYSIS_LIMIT = null; // null = unlimited in v1.

  private final UsageRepository usageRepository;
  private final Clock clock;

  public UsageService(UsageRepository usageRepository, Clock clock) {
    this.usageRepository = usageRepository;
    this.clock = clock;
  }

  public UsageTodayResponse getToday(InternalAuthPrincipal principal) {
    UUID userId = requireAuthenticatedUser(principal);

    OffsetDateTime startOfDay =
        LocalDate.now(clock.withZone(ZoneOffset.UTC)).atStartOfDay().atOffset(ZoneOffset.UTC);
    OffsetDateTime startOfNextDay = startOfDay.plusDays(1);

    int roleplayCount = usageRepository.countRoleplaySessions(userId, startOfDay, startOfNextDay);
    int analysisCount = usageRepository.countAnalysisRequests(userId, startOfDay, startOfNextDay);

    return new UsageTodayResponse(
        roleplayCount, DAILY_ROLEPLAY_LIMIT, analysisCount, ANALYSIS_LIMIT);
  }

  private static UUID requireAuthenticatedUser(InternalAuthPrincipal principal) {
    if (principal == null || !principal.isAuthenticatedUser()) {
      throw new ApiErrorException(
          HttpStatus.UNAUTHORIZED,
          "internal_auth_invalid",
          "로그인이 필요해요.",
          "GET /usage/today requires an authenticated user_id principal.",
          false);
    }
    try {
      return UUID.fromString(principal.userId());
    } catch (IllegalArgumentException e) {
      throw new ApiErrorException(
          HttpStatus.BAD_REQUEST,
          "validation_failed",
          "입력값을 다시 확인해 주세요.",
          "user_id claim must be a UUID.",
          false);
    }
  }
}
