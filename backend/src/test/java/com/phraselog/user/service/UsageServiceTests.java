package com.phraselog.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.ApiErrorException;
import com.phraselog.user.dto.UsageTodayResponse;
import com.phraselog.user.repository.UsageRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class UsageServiceTests {

  private static final OffsetDateTime START_OF_DAY = OffsetDateTime.parse("2026-06-21T00:00:00Z");
  private static final OffsetDateTime START_OF_NEXT_DAY =
      OffsetDateTime.parse("2026-06-22T00:00:00Z");

  @Test
  void usesUtcDayHalfOpenRangeAndReportsV1Limits() {
    UsageRepository repository = mock(UsageRepository.class);
    // Mid-day so the start-of-day computation is non-trivial.
    Clock clock = Clock.fixed(Instant.parse("2026-06-21T10:30:00Z"), ZoneOffset.UTC);
    UsageService service = new UsageService(repository, clock);
    UUID userId = UUID.randomUUID();

    when(repository.countRoleplaySessions(eq(userId), eq(START_OF_DAY), eq(START_OF_NEXT_DAY)))
        .thenReturn(1);
    when(repository.countAnalysisRequests(eq(userId), eq(START_OF_DAY), eq(START_OF_NEXT_DAY)))
        .thenReturn(5);

    UsageTodayResponse response = service.getToday(InternalAuthPrincipal.ofUser(userId.toString()));

    assertThat(response.roleplaySessionCount()).isEqualTo(1);
    assertThat(response.dailyRoleplayLimit()).isEqualTo(2);
    assertThat(response.analysisCount()).isEqualTo(5);
    assertThat(response.analysisLimit()).isNull();

    verify(repository).countRoleplaySessions(userId, START_OF_DAY, START_OF_NEXT_DAY);
    verify(repository).countAnalysisRequests(userId, START_OF_DAY, START_OF_NEXT_DAY);
  }

  @Test
  void boundaryIsComputedFromTheClockInstantNotWallClock() {
    UsageRepository repository = mock(UsageRepository.class);
    // One second before midnight UTC: still "today" = 2026-06-21.
    Clock clock = Clock.fixed(Instant.parse("2026-06-21T23:59:59Z"), ZoneOffset.UTC);
    UsageService service = new UsageService(repository, clock);
    UUID userId = UUID.randomUUID();

    service.getToday(InternalAuthPrincipal.ofUser(userId.toString()));

    verify(repository).countRoleplaySessions(userId, START_OF_DAY, START_OF_NEXT_DAY);
    verify(repository).countAnalysisRequests(userId, START_OF_DAY, START_OF_NEXT_DAY);
  }

  @Test
  void rejectsAnonymousSessionPrincipalWith401() {
    UsageRepository repository = mock(UsageRepository.class);
    Clock clock = Clock.fixed(Instant.parse("2026-06-21T10:30:00Z"), ZoneOffset.UTC);
    UsageService service = new UsageService(repository, clock);

    assertThatThrownBy(() -> service.getToday(InternalAuthPrincipal.ofSession("anon-token")))
        .isInstanceOfSatisfying(
            ApiErrorException.class,
            e -> assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED));
  }
}
