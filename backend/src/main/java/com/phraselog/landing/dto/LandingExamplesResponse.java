package com.phraselog.landing.dto;

import java.util.List;

/** Envelope for {@code GET /landing/examples} (#32): {@code { "examples": [...] }} per openapi. */
public record LandingExamplesResponse(List<LandingExampleResponse> examples) {}
