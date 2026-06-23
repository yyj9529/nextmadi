package com.phraselog.home.service;

import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.coach.dto.CoachResponse;
import com.phraselog.coach.repository.CoachRepository;
import com.phraselog.common.web.ApiErrorException;
import com.phraselog.expression.dto.ExpressionListItem;
import com.phraselog.expression.repository.ExpressionRepository;
import com.phraselog.home.dto.DashboardResponse;
import com.phraselog.review.repository.ReviewRepository;
import com.phraselog.user.dto.UserResponse;
import com.phraselog.user.repository.UsageRepository;
import com.phraselog.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backend core for {@code GET /home/dashboard} (#54). Aggregates the S04 first-paint state from the
 * per-feature repositories in one HTTP round trip; each call is a constant-count query (no N+1).
 *
 * <p>"Today" is the UTC calendar day {@code [startOfDay, startOfNextDay)} driven by an injected
 * {@link Clock}, matching {@code GET /usage/today} so the home and usage screens agree.
 */
@Service
public class HomeService {

  private static final Logger log = LoggerFactory.getLogger(HomeService.class);

  private static final int RECENT_EXPRESSION_LIMIT = 2;
  private static final int DAILY_ROLEPLAY_LIMIT = 2; // v1; mirrors UsageService.

  private final UserRepository userRepository;
  private final CoachRepository coachRepository;
  private final ExpressionRepository expressionRepository;
  private final ReviewRepository reviewRepository;
  private final UsageRepository usageRepository;
  private final Clock clock;

  public HomeService(
      UserRepository userRepository,
      CoachRepository coachRepository,
      ExpressionRepository expressionRepository,
      ReviewRepository reviewRepository,
      UsageRepository usageRepository,
      Clock clock) {
    this.userRepository = userRepository;
    this.coachRepository = coachRepository;
    this.expressionRepository = expressionRepository;
    this.reviewRepository = reviewRepository;
    this.usageRepository = usageRepository;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public DashboardResponse dashboard(InternalAuthPrincipal principal) {
    UUID userId = requireAuthenticatedUser(principal);

    UserResponse user = userRepository.findById(userId).orElseThrow(HomeService::userNotFound);
    CoachResponse coach = resolveCoach(user);

    int bookshelfCount = expressionRepository.countActive(userId);
    List<ExpressionListItem> recent =
        expressionRepository.list(userId, null, null, null, RECENT_EXPRESSION_LIMIT);
    int dueReviewCount = reviewRepository.countDue(userId);

    OffsetDateTime startOfDay =
        LocalDate.now(clock.withZone(ZoneOffset.UTC)).atStartOfDay().atOffset(ZoneOffset.UTC);
    OffsetDateTime startOfNextDay = startOfDay.plusDays(1);
    int roleplayCount = usageRepository.countRoleplaySessions(userId, startOfDay, startOfNextDay);

    return new DashboardResponse(
        user, coach, bookshelfCount, recent, dueReviewCount, roleplayCount, DAILY_ROLEPLAY_LIMIT);
  }

  /**
   * Resolves the greeting coach. Null selected coach is a tolerated post-onboarding state (s04.md):
   * respond with a null coach and let the client fall back. A non-null id that does not resolve is
   * a data-integrity anomaly, so it is logged but still degrades to a fallback rather than failing.
   */
  private CoachResponse resolveCoach(UserResponse user) {
    UUID selectedCoachId = user.selectedCoachId();
    if (selectedCoachId == null) {
      log.warn("User {} has no selected_coach_id; home greeting falls back", user.id());
      return null;
    }
    return coachRepository
        .findById(selectedCoachId)
        .orElseGet(
            () -> {
              log.warn(
                  "User {} references unknown coach {}; home greeting falls back",
                  user.id(),
                  selectedCoachId);
              return null;
            });
  }

  private static UUID requireAuthenticatedUser(InternalAuthPrincipal principal) {
    if (principal == null || !principal.isAuthenticatedUser()) {
      throw new ApiErrorException(
          HttpStatus.UNAUTHORIZED,
          "internal_auth_invalid",
          "로그인이 필요해요.",
          "GET /home/dashboard requires an authenticated user_id principal.",
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

  private static ApiErrorException userNotFound() {
    return new ApiErrorException(
        HttpStatus.NOT_FOUND,
        "not_found",
        "사용자를 찾을 수 없어요.",
        "Authenticated user_id has no active users row.",
        false);
  }
}
