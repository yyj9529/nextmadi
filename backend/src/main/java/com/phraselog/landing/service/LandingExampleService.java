package com.phraselog.landing.service;

import com.phraselog.landing.dto.LandingExampleResponse;
import com.phraselog.landing.repository.LandingExampleRepository;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Backend core for {@code GET /landing/examples} (#32). Public endpoint ({@code security: []} in
 * openapi, InternalAuthFilter whitelist) — no principal is required. Returns up to three random
 * active examples; an empty pool yields an empty list, which S01 renders as CTA-only.
 */
@Service
public class LandingExampleService {

  static final int SAMPLE_SIZE = 3;

  private final LandingExampleRepository landingExampleRepository;

  public LandingExampleService(LandingExampleRepository landingExampleRepository) {
    this.landingExampleRepository = landingExampleRepository;
  }

  public List<LandingExampleResponse> sample() {
    return landingExampleRepository.findRandomActive(SAMPLE_SIZE);
  }
}
