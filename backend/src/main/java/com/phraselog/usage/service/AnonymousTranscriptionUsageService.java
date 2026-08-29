package com.phraselog.usage.service;

import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.ApiErrorException;
import com.phraselog.usage.repository.AnonymousTranscriptionUsageRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;

/**
 * 가입 전 사용자의 하루 STT 호출 수를 IP 기준으로 제한한다.
 *
 * <p>Whisper 는 호출당 과금이고 {@code POST /transcriptions} 는 로그인 없이 열려 있으므로, 한도가 없으면 비용이 무제한으로 노출된다.
 * {@link AnonymousAnalysisUsageService} 와 같은 예약/해제 패턴을 쓰되 카운터는 분리돼 있다 — 전사가 분석 예산을 태우면 2-스텝 분리가
 * 무의미해진다.
 *
 * <p>한도가 분석(2회)보다 넉넉한 이유: 사용자는 보통 말을 여러 번 다듬은 뒤 한 번 제출한다. 분석 2회 × 재녹음 여유 5회를 잡아 10회로 둔다. 60초 상한이
 * 있으므로 IP 당 하루 최악이 10분치 Whisper 다.
 */
public class AnonymousTranscriptionUsageService {

  private static final int DEFAULT_DAILY_LIMIT = 10;

  private final AnonymousTranscriptionUsageRepository repository;
  private final ClientIpResolver clientIpResolver;
  private final Clock clock;
  private final int dailyLimit;

  public AnonymousTranscriptionUsageService(
      AnonymousTranscriptionUsageRepository repository, ClientIpResolver clientIpResolver) {
    this(repository, clientIpResolver, Clock.systemDefaultZone(), DEFAULT_DAILY_LIMIT);
  }

  AnonymousTranscriptionUsageService(
      AnonymousTranscriptionUsageRepository repository,
      ClientIpResolver clientIpResolver,
      Clock clock,
      int dailyLimit) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.clientIpResolver = Objects.requireNonNull(clientIpResolver, "clientIpResolver");
    this.clock = Objects.requireNonNull(clock, "clock");
    if (dailyLimit < 1) {
      throw new IllegalArgumentException("dailyLimit must be positive");
    }
    this.dailyLimit = dailyLimit;
  }

  public <T> T withAnonymousTranscriptionLimit(
      InternalAuthPrincipal principal, String clientIpHeader, Supplier<T> transcriptionWork) {
    Objects.requireNonNull(principal, "principal");
    Objects.requireNonNull(transcriptionWork, "transcriptionWork");

    if (principal.isAuthenticatedUser()) {
      return transcriptionWork.get();
    }

    String clientIp = clientIpResolver.resolveRequired(clientIpHeader);
    LocalDate usageDate = LocalDate.now(clock);
    if (!repository.tryReserve(clientIp, usageDate, dailyLimit)) {
      throw rateLimitExceeded();
    }

    try {
      return transcriptionWork.get();
    } catch (RuntimeException | Error e) {
      if (refundable(e)) {
        repository.release(clientIp, usageDate);
      }
      throw e;
    }
  }

  /**
   * 과금이 발생하지 않은 실패만 한도를 돌려준다.
   *
   * <p>무발화({@code empty_transcript})는 Whisper 가 이미 호출·과금된 뒤의 결과다. 이것까지 돌려주면 무음을 반복 전송해 한도를 전혀 쓰지 않고
   * 비용만 태울 수 있어, 이 한도의 목적이 무너진다. 반면 규격 오류는 공급자 호출 전에 걸러지고, 공급자 오류·타임아웃은 사용자 잘못이 아니므로 둘 다 하루치를 깎지
   * 않는다.
   */
  private static boolean refundable(Throwable e) {
    return !(e instanceof ApiErrorException api) || !"empty_transcript".equals(api.errorCode());
  }

  private static ApiErrorException rateLimitExceeded() {
    return new ApiErrorException(
        HttpStatus.TOO_MANY_REQUESTS,
        "rate_limit_exceeded",
        "오늘 사용할 수 있는 음성 입력 횟수를 모두 썼어요. 텍스트로 입력해보세요.",
        "Check anonymous_transcription_usage for this client IP and date.",
        true);
  }
}
