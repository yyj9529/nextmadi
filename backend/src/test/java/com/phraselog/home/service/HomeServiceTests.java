package com.phraselog.home.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class HomeServiceTests {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-06-21T10:30:00Z"), ZoneOffset.UTC);
  private static final OffsetDateTime START_OF_DAY = OffsetDateTime.parse("2026-06-21T00:00:00Z");
  private static final OffsetDateTime START_OF_NEXT_DAY =
      OffsetDateTime.parse("2026-06-22T00:00:00Z");

  private UserRepository userRepository;
  private CoachRepository coachRepository;
  private ExpressionRepository expressionRepository;
  private ReviewRepository reviewRepository;
  private UsageRepository usageRepository;
  private HomeService service;

  @BeforeEach
  void setUp() {
    userRepository = mock(UserRepository.class);
    coachRepository = mock(CoachRepository.class);
    expressionRepository = mock(ExpressionRepository.class);
    reviewRepository = mock(ReviewRepository.class);
    usageRepository = mock(UsageRepository.class);
    service =
        new HomeService(
            userRepository,
            coachRepository,
            expressionRepository,
            reviewRepository,
            usageRepository,
            FIXED_CLOCK);
  }

  @Test
  void aggregatesAllSectionsInOneResponse() {
    UUID userId = UUID.randomUUID();
    UUID coachId = UUID.randomUUID();
    UserResponse user = user(userId, coachId);
    CoachResponse coach = new CoachResponse(coachId, "mia", "Mia", "친절한 코치.", "shimmer");
    List<ExpressionListItem> recent =
        List.of(
            new ExpressionListItem(UUID.randomUUID(), "상황 A", "Say this.", "정중한", START_OF_DAY),
            new ExpressionListItem(UUID.randomUUID(), "상황 B", "Or this.", "부드러운", START_OF_DAY));

    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(coachRepository.findById(coachId)).thenReturn(Optional.of(coach));
    when(expressionRepository.countActive(userId)).thenReturn(7);
    when(expressionRepository.list(userId, null, null, null, 2)).thenReturn(recent);
    when(reviewRepository.countDue(userId)).thenReturn(3);
    when(usageRepository.countRoleplaySessions(eq(userId), eq(START_OF_DAY), eq(START_OF_NEXT_DAY)))
        .thenReturn(1);

    DashboardResponse response = service.dashboard(InternalAuthPrincipal.ofUser(userId.toString()));

    assertThat(response.user()).isEqualTo(user);
    assertThat(response.coach()).isEqualTo(coach);
    assertThat(response.bookshelfCount()).isEqualTo(7);
    assertThat(response.recentExpressions()).isEqualTo(recent);
    assertThat(response.dueReviewCount()).isEqualTo(3);
    assertThat(response.todayRoleplaySessionCount()).isEqualTo(1);
    assertThat(response.dailyRoleplayLimit()).isEqualTo(2);

    verify(usageRepository).countRoleplaySessions(userId, START_OF_DAY, START_OF_NEXT_DAY);
  }

  @Test
  void newUserGetsZerosAndEmptyRecentList() {
    UUID userId = UUID.randomUUID();
    UUID coachId = UUID.randomUUID();
    when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId, coachId)));
    when(coachRepository.findById(coachId))
        .thenReturn(Optional.of(new CoachResponse(coachId, "mia", "Mia", "친절한 코치.", "shimmer")));
    when(expressionRepository.countActive(userId)).thenReturn(0);
    when(expressionRepository.list(userId, null, null, null, 2)).thenReturn(List.of());
    when(reviewRepository.countDue(userId)).thenReturn(0);
    when(usageRepository.countRoleplaySessions(userId, START_OF_DAY, START_OF_NEXT_DAY))
        .thenReturn(0);

    DashboardResponse response = service.dashboard(InternalAuthPrincipal.ofUser(userId.toString()));

    assertThat(response.bookshelfCount()).isZero();
    assertThat(response.recentExpressions()).isEmpty();
    assertThat(response.dueReviewCount()).isZero();
    assertThat(response.todayRoleplaySessionCount()).isZero();
    assertThat(response.dailyRoleplayLimit()).isEqualTo(2);
  }

  @Test
  void nullSelectedCoachDegradesToNullCoachWithoutLookup() {
    UUID userId = UUID.randomUUID();
    when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId, null)));
    when(expressionRepository.countActive(userId)).thenReturn(0);
    when(expressionRepository.list(userId, null, null, null, 2)).thenReturn(List.of());
    when(reviewRepository.countDue(userId)).thenReturn(0);
    when(usageRepository.countRoleplaySessions(userId, START_OF_DAY, START_OF_NEXT_DAY))
        .thenReturn(0);

    DashboardResponse response = service.dashboard(InternalAuthPrincipal.ofUser(userId.toString()));

    assertThat(response.coach()).isNull();
  }

  @Test
  void unknownSelectedCoachDegradesToNullCoach() {
    UUID userId = UUID.randomUUID();
    UUID coachId = UUID.randomUUID();
    when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId, coachId)));
    when(coachRepository.findById(coachId)).thenReturn(Optional.empty());
    when(expressionRepository.countActive(userId)).thenReturn(0);
    when(expressionRepository.list(userId, null, null, null, 2)).thenReturn(List.of());
    when(reviewRepository.countDue(userId)).thenReturn(0);
    when(usageRepository.countRoleplaySessions(userId, START_OF_DAY, START_OF_NEXT_DAY))
        .thenReturn(0);

    DashboardResponse response = service.dashboard(InternalAuthPrincipal.ofUser(userId.toString()));

    assertThat(response.coach()).isNull();
  }

  @Test
  void rejectsAnonymousSessionPrincipalWith401() {
    assertThatThrownBy(() -> service.dashboard(InternalAuthPrincipal.ofSession("anon-token")))
        .isInstanceOfSatisfying(
            ApiErrorException.class,
            e -> assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED));
  }

  @Test
  void missingUserRowReturns404() {
    UUID userId = UUID.randomUUID();
    when(userRepository.findById(userId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.dashboard(InternalAuthPrincipal.ofUser(userId.toString())))
        .isInstanceOfSatisfying(
            ApiErrorException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  private static UserResponse user(UUID userId, UUID selectedCoachId) {
    return new UserResponse(
        userId, "owner@example.com", "우주", selectedCoachId, true, START_OF_DAY, null);
  }
}
