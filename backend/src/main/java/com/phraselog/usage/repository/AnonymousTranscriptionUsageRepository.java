package com.phraselog.usage.repository;

import java.time.LocalDate;

/**
 * {@link AnonymousAnalysisUsageRepository} 와 같은 계약이지만 다른 테이블을 센다. 전사와 분석은 예산을 공유하지 않는다 — V009
 * 마이그레이션 주석 참고.
 */
public interface AnonymousTranscriptionUsageRepository {

  boolean tryReserve(String ipAddress, LocalDate usageDate, int limit);

  void release(String ipAddress, LocalDate usageDate);
}
