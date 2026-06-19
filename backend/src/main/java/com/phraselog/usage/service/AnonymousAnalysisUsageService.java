package com.phraselog.usage.service;

import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.ApiErrorException;
import com.phraselog.usage.repository.AnonymousAnalysisUsageRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;

public class AnonymousAnalysisUsageService {

  private static final int DEFAULT_DAILY_LIMIT = 2;

  private final AnonymousAnalysisUsageRepository repository;
  private final ClientIpResolver clientIpResolver;
  private final Clock clock;
  private final int dailyLimit;

  public AnonymousAnalysisUsageService(
      AnonymousAnalysisUsageRepository repository, ClientIpResolver clientIpResolver) {
    this(repository, clientIpResolver, Clock.systemDefaultZone(), DEFAULT_DAILY_LIMIT);
  }

  AnonymousAnalysisUsageService(
      AnonymousAnalysisUsageRepository repository,
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

  public <T> T withAnonymousAnalysisLimit(
      InternalAuthPrincipal principal, String clientIpHeader, Supplier<T> analysisWork) {
    Objects.requireNonNull(principal, "principal");
    Objects.requireNonNull(analysisWork, "analysisWork");

    if (principal.isAuthenticatedUser()) {
      return analysisWork.get();
    }

    String clientIp = clientIpResolver.resolveRequired(clientIpHeader);
    LocalDate usageDate = LocalDate.now(clock);
    if (!repository.tryReserve(clientIp, usageDate, dailyLimit)) {
      throw rateLimitExceeded();
    }

    try {
      return analysisWork.get();
    } catch (RuntimeException | Error e) {
      repository.release(clientIp, usageDate);
      throw e;
    }
  }

  private static ApiErrorException rateLimitExceeded() {
    return new ApiErrorException(
        HttpStatus.TOO_MANY_REQUESTS,
        "rate_limit_exceeded",
        "?ㅻ뒛 ?ъ슜?????덈뒗 ?잛닔瑜?紐⑤몢 ?쇱뼱??",
        "Check anonymous_analysis_usage or per-user daily limit counters.",
        true);
  }
}
