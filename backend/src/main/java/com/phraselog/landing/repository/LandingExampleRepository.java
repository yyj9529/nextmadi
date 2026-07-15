package com.phraselog.landing.repository;

import com.phraselog.landing.dto.LandingExampleResponse;
import java.util.List;

/** Persistence boundary for the S01 landing example pool (#32). */
public interface LandingExampleRepository {

  /**
   * A random sample of up to {@code limit} active examples. Empty when the active pool is empty.
   * Each call re-samples, so successive visits differ (s01: "다른 무작위 표본").
   */
  List<LandingExampleResponse> findRandomActive(int limit);
}
