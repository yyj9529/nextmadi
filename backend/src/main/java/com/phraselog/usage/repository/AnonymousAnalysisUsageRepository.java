package com.phraselog.usage.repository;

import java.time.LocalDate;

public interface AnonymousAnalysisUsageRepository {

  boolean tryReserve(String ipAddress, LocalDate usageDate, int limit);

  void release(String ipAddress, LocalDate usageDate);
}
