package com.phraselog.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.phraselog.auth.InternalAuthPrincipal;
import com.phraselog.common.web.ApiErrorException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AnonymousAnalysisUsageServiceTests {

  private final MutableClock clock =
      new MutableClock(Instant.parse("2026-06-19T15:00:00Z"), ZoneOffset.UTC);
  private FakeAnonymousAnalysisUsageRepository repository;
  private AnonymousAnalysisUsageService service;

  @BeforeEach
  void setUp() {
    repository = new FakeAnonymousAnalysisUsageRepository();
    service =
        new AnonymousAnalysisUsageService(repository, new ClientIpResolver(), clock, 2);
  }

  @Test
  void authenticatedPrincipalBypassesAnonymousUsageLimit() {
    InternalAuthPrincipal principal = new InternalAuthPrincipal("user-1", null);
    AtomicInteger calls = new AtomicInteger();

    String result =
        service.withAnonymousAnalysisLimit(
            principal,
            null,
            () -> {
              calls.incrementAndGet();
              return "created";
            });

    assertThat(result).isEqualTo("created");
    assertThat(calls).hasValue(1);
    assertThat(repository.reserveCalls).isZero();
    assertThat(repository.releaseCalls).isZero();
  }

  @Test
  void anonymousPrincipalReservesBeforeRunningAnalysisWork() {
    AtomicInteger calls = new AtomicInteger();

    String result =
        service.withAnonymousAnalysisLimit(
            anonymous("session-1"),
            "203.0.113.10",
            () -> {
              assertThat(repository.currentCount("203.0.113.10", LocalDate.of(2026, 6, 19)))
                  .isEqualTo(1);
              calls.incrementAndGet();
              return "created";
            });

    assertThat(result).isEqualTo("created");
    assertThat(calls).hasValue(1);
    assertThat(repository.currentCount("203.0.113.10", LocalDate.of(2026, 6, 19))).isEqualTo(1);
    assertThat(repository.releaseCalls).isZero();
  }

  @Test
  void exhaustedAnonymousQuotaReturns429BeforeRunningAnalysisWork() {
    service.withAnonymousAnalysisLimit(anonymous("session-1"), "203.0.113.10", () -> "first");
    service.withAnonymousAnalysisLimit(anonymous("session-2"), "203.0.113.10", () -> "second");
    AtomicInteger calls = new AtomicInteger();

    assertThatThrownBy(
            () ->
                service.withAnonymousAnalysisLimit(
                    anonymous("session-3"),
                    "203.0.113.10",
                    () -> {
                      calls.incrementAndGet();
                      return "third";
                    }))
        .isInstanceOfSatisfying(
            ApiErrorException.class,
            error -> {
              assertThat(error.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
              assertThat(error.errorCode()).isEqualTo("rate_limit_exceeded");
              assertThat(error.retryable()).isTrue();
            });

    assertThat(calls).hasValue(0);
    assertThat(repository.currentCount("203.0.113.10", LocalDate.of(2026, 6, 19))).isEqualTo(2);
  }

  @Test
  void failedAnalysisWorkReleasesReservationSoOnlySuccessfulAnalysesRemainCounted() {
    assertThatThrownBy(
            () ->
                service.withAnonymousAnalysisLimit(
                    anonymous("session-1"),
                    "203.0.113.10",
                    () -> {
                      throw new ApiErrorException(
                          HttpStatus.BAD_REQUEST,
                          "validation_failed",
                          "invalid",
                          "test failure",
                          false);
                    }))
        .isInstanceOf(ApiErrorException.class);

    assertThat(repository.currentCount("203.0.113.10", LocalDate.of(2026, 6, 19))).isZero();
    assertThat(repository.releaseCalls).isEqualTo(1);

    service.withAnonymousAnalysisLimit(anonymous("session-2"), "203.0.113.10", () -> "first");
    service.withAnonymousAnalysisLimit(anonymous("session-3"), "203.0.113.10", () -> "second");

    assertThat(repository.currentCount("203.0.113.10", LocalDate.of(2026, 6, 19))).isEqualTo(2);
  }

  @Test
  void sameIpWithDifferentSessionTokensSharesQuota() {
    service.withAnonymousAnalysisLimit(anonymous("tab-1"), "203.0.113.10", () -> "first");
    service.withAnonymousAnalysisLimit(anonymous("tab-2"), "203.0.113.10", () -> "second");

    assertThatThrownBy(
            () ->
                service.withAnonymousAnalysisLimit(
                    anonymous("cleared-session-storage"), "203.0.113.10", () -> "third"))
        .isInstanceOfSatisfying(
            ApiErrorException.class,
            error -> assertThat(error.errorCode()).isEqualTo("rate_limit_exceeded"));
  }

  @Test
  void differentIpAndNextServerLocalDateHaveIndependentQuota() {
    service.withAnonymousAnalysisLimit(anonymous("session-1"), "203.0.113.10", () -> "first");
    service.withAnonymousAnalysisLimit(anonymous("session-2"), "203.0.113.10", () -> "second");
    service.withAnonymousAnalysisLimit(anonymous("session-3"), "198.51.100.12", () -> "other-ip");

    clock.setInstant(Instant.parse("2026-06-20T00:01:00Z"));

    String nextDay =
        service.withAnonymousAnalysisLimit(anonymous("session-4"), "203.0.113.10", () -> "next");

    assertThat(nextDay).isEqualTo("next");
    assertThat(repository.currentCount("203.0.113.10", LocalDate.of(2026, 6, 19))).isEqualTo(2);
    assertThat(repository.currentCount("203.0.113.10", LocalDate.of(2026, 6, 20))).isEqualTo(1);
    assertThat(repository.currentCount("198.51.100.12", LocalDate.of(2026, 6, 19))).isEqualTo(1);
  }

  private static InternalAuthPrincipal anonymous(String token) {
    return new InternalAuthPrincipal(null, token);
  }

  private static final class FakeAnonymousAnalysisUsageRepository
      implements AnonymousAnalysisUsageRepository {

    private final Map<Key, Integer> counts = new HashMap<>();
    int reserveCalls;
    int releaseCalls;

    @Override
    public boolean tryReserve(String ipAddress, LocalDate usageDate, int limit) {
      reserveCalls++;
      Key key = new Key(ipAddress, usageDate);
      int current = counts.getOrDefault(key, 0);
      if (current >= limit) {
        return false;
      }
      counts.put(key, current + 1);
      return true;
    }

    @Override
    public void release(String ipAddress, LocalDate usageDate) {
      releaseCalls++;
      Key key = new Key(ipAddress, usageDate);
      counts.put(key, Math.max(0, counts.getOrDefault(key, 0) - 1));
    }

    int currentCount(String ipAddress, LocalDate usageDate) {
      return counts.getOrDefault(new Key(ipAddress, usageDate), 0);
    }
  }

  private record Key(String ipAddress, LocalDate usageDate) {}

  private static final class MutableClock extends Clock {

    private ZoneId zone;
    private Instant instant;

    private MutableClock(Instant instant, ZoneId zone) {
      this.instant = instant;
      this.zone = zone;
    }

    void setInstant(Instant instant) {
      this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      this.zone = zone;
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
