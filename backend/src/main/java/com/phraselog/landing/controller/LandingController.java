package com.phraselog.landing.controller;

import com.phraselog.common.web.ApiPaths;
import com.phraselog.landing.dto.LandingExamplesResponse;
import com.phraselog.landing.service.LandingExampleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * S01 public landing data (#32). Whitelisted in {@link com.phraselog.auth.web.InternalAuthFilter}
 * (openapi {@code security: []}), so no principal is extracted; the controller stays thin.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/landing")
public class LandingController {

  private final LandingExampleService landingExampleService;

  public LandingController(LandingExampleService landingExampleService) {
    this.landingExampleService = landingExampleService;
  }

  @GetMapping("/examples")
  public LandingExamplesResponse examples() {
    return new LandingExamplesResponse(landingExampleService.sample());
  }
}
