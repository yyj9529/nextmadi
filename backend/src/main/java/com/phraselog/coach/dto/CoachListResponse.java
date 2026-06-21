package com.phraselog.coach.dto;

import java.util.List;

/** Envelope for {@code GET /coaches} (#52): {@code { "coaches": [...] }} per openapi. */
public record CoachListResponse(List<CoachResponse> coaches) {}
