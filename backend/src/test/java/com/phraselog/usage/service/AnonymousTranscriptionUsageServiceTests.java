package com.phraselog.usage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.ApiErrorException;
import com.phraselog.usage.repository.AnonymousTranscriptionUsageRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AnonymousTranscriptionUsageServiceTests {

  private static final Clock FIXED =
      Clock.fixed(Instant.parse("2026-08-27T03:00:00Z"), ZoneOffset.UTC);
  private static final LocalDate TODAY = LocalDate.of(2026, 8, 27);
  private static final String IP = "203.0.113.7";

  /** (ip, date) 별 count 를 들고 실제 SQL 과 같은 예약/해제 의미를 흉내낸다. */
  private static final class InMemoryRepository implements AnonymousTranscriptionUsageRepository {
    private final Map<String, Integer> counts = new HashMap<>();
    private final List<String> releases = new ArrayList<>();

    @Override
    public boolean tryReserve(String ipAddress, LocalDate usageDate, int limit) {
      String key = ipAddress + "|" + usageDate;
      int current = counts.getOrDefault(key, 0);
      if (current >= limit) {
        return false;
      }
      counts.put(key, current + 1);
      return true;
    }

    @Override
    public void release(String ipAddress, LocalDate usageDate) {
      String key = ipAddress + "|" + usageDate;
      releases.add(key);
      counts.computeIfPresent(key, (k, v) -> Math.max(v - 1, 0));
    }

    int count() {
      return counts.getOrDefault(IP + "|" + TODAY, 0);
    }
  }

  private AnonymousTranscriptionUsageService service(InMemoryRepository repository, int limit) {
    return new AnonymousTranscriptionUsageService(repository, new ClientIpResolver(), FIXED, limit);
  }

  private static ApiErrorException apiError(HttpStatus status, String errorCode) {
    return new ApiErrorException(status, errorCode, "user", "hint", false);
  }

  @Test
  void anonymousCallsConsumeTheDailyBudgetAndAreRejectedAtTheLimit() {
    InMemoryRepository repository = new InMemoryRepository();
    AnonymousTranscriptionUsageService service = service(repository, 2);
    InternalAuthPrincipal anonymous = InternalAuthPrincipal.ofSession("anon");

    assertThat(service.withAnonymousTranscriptionLimit(anonymous, IP, () -> "ok")).isEqualTo("ok");
    assertThat(service.withAnonymousTranscriptionLimit(anonymous, IP, () -> "ok")).isEqualTo("ok");

    assertThatThrownBy(() -> service.withAnonymousTranscriptionLimit(anonymous, IP, () -> "ok"))
        .isInstanceOf(ApiErrorException.class)
        .satisfies(
            error -> {
              ApiErrorException api = (ApiErrorException) error;
              assertThat(api.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
              assertThat(api.errorCode()).isEqualTo("rate_limit_exceeded");
            });
  }

  // 이 한도의 목적은 Whisper 비용을 막는 것이다. 무발화는 이미 과금된 뒤의 결과이므로
  // 돌려주면 무음을 반복 전송해 한도를 전혀 쓰지 않고 비용만 태울 수 있다.
  @Test
  void emptyTranscriptStillConsumesTheBudgetBecauseWhisperWasBilled() {
    InMemoryRepository repository = new InMemoryRepository();
    AnonymousTranscriptionUsageService service = service(repository, 10);

    assertThatThrownBy(
            () ->
                service.withAnonymousTranscriptionLimit(
                    InternalAuthPrincipal.ofSession("anon"),
                    IP,
                    () -> {
                      throw apiError(HttpStatus.UNPROCESSABLE_ENTITY, "empty_transcript");
                    }))
        .isInstanceOf(ApiErrorException.class);

    assertThat(repository.releases).isEmpty();
    assertThat(repository.count()).isEqualTo(1);
  }

  // 규격 오류는 공급자 호출 전에 걸러지고, 공급자 오류·타임아웃은 사용자 잘못이 아니다.
  @Test
  void failuresWithNoProviderChargeGiveTheBudgetBack() {
    for (String errorCode : List.of("validation_failed", "provider_5xx", "timeout", "network")) {
      InMemoryRepository repository = new InMemoryRepository();
      AnonymousTranscriptionUsageService service = service(repository, 10);

      assertThatThrownBy(
              () ->
                  service.withAnonymousTranscriptionLimit(
                      InternalAuthPrincipal.ofSession("anon"),
                      IP,
                      () -> {
                        throw apiError(HttpStatus.BAD_REQUEST, errorCode);
                      }))
          .isInstanceOf(ApiErrorException.class);

      assertThat(repository.count()).as(errorCode).isZero();
    }
  }

  @Test
  void unexpectedRuntimeFailuresAlsoGiveTheBudgetBack() {
    InMemoryRepository repository = new InMemoryRepository();
    AnonymousTranscriptionUsageService service = service(repository, 10);

    assertThatThrownBy(
            () ->
                service.withAnonymousTranscriptionLimit(
                    InternalAuthPrincipal.ofSession("anon"),
                    IP,
                    () -> {
                      throw new IllegalStateException("boom");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(repository.count()).isZero();
  }

  // 한도는 가입 전 사용자 전용이다. 로그인 사용자는 IP 헤더가 없어도 통과해야 한다.
  @Test
  void authenticatedCallersBypassTheLimitEntirely() {
    InMemoryRepository repository = new InMemoryRepository();
    AnonymousTranscriptionUsageService service = service(repository, 1);
    InternalAuthPrincipal user =
        InternalAuthPrincipal.ofUser("11111111-1111-1111-1111-111111111111");

    for (int i = 0; i < 5; i++) {
      assertThat(service.withAnonymousTranscriptionLimit(user, null, () -> "ok")).isEqualTo("ok");
    }
    assertThat(repository.count()).isZero();
  }
}
