package com.phraselog.review.dto;

/** {@code POST /review/{id}/submit} body: the self-evaluation rating (#49 S10). */
public record SubmitRatingRequest(String rating) {}
