package com.phraselog.expression.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.phraselog.analysis.dto.AnalysisRequestRow;
import com.phraselog.analysis.repository.AnalysisRepository;
import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.ApiErrorException;
import com.phraselog.expression.dto.CreateExpressionRequest;
import com.phraselog.expression.dto.NewExpression;
import com.phraselog.expression.dto.NewExpressionVariant;
import com.phraselog.expression.dto.SaveExpressionResult;
import com.phraselog.expression.repository.ExpressionRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Backend core for saving S07 analysis output into the expression library (#41). */
@Service
public class ExpressionService {

  private static final int DEFAULT_SELECTED_VARIANT_ORDER = 1;
  private static final int EXPECTED_VARIANT_COUNT = 3;

  private final AnalysisRepository analysisRepository;
  private final ExpressionRepository expressionRepository;

  public ExpressionService(
      AnalysisRepository analysisRepository, ExpressionRepository expressionRepository) {
    this.analysisRepository = analysisRepository;
    this.expressionRepository = expressionRepository;
  }

  @Transactional
  public SaveExpressionResult create(
      InternalAuthPrincipal principal, CreateExpressionRequest body, String idempotencyKeyHeader) {
    UUID userId = requireAuthenticatedUser(principal);
    validateIdempotencyKey(idempotencyKeyHeader);

    UUID analysisRequestId = requireAnalysisRequestId(body);
    int selectedVariantOrder = selectedVariantOrder(body);
    String claimSessionToken = body == null ? null : body.sessionToken();

    Optional<com.phraselog.expression.dto.ExpressionResponse> existing =
        expressionRepository.findByAnalysisIdForUser(analysisRequestId, userId);
    if (existing.isPresent()) {
      return new SaveExpressionResult(existing.get(), true);
    }

    AnalysisRequestRow analysis =
        resolveOwnedOrClaimed(analysisRequestId, userId, claimSessionToken);

    NewExpression command =
        new NewExpression(
            userId,
            analysis.id(),
            analysis.inputText(),
            selectedVariantOrder,
            variantsFrom(analysis.outputJson()));

    return new SaveExpressionResult(expressionRepository.createFromAnalysis(command), false);
  }

  /**
   * Resolves the analysis the authenticated user is saving, honoring the pending-save claim (#42).
   *
   * <ol>
   *   <li>Already owned by this {@code userId} → return it (ordinary authenticated save).
   *   <li>Otherwise, if a {@code claimSessionToken} is present, atomically claim the row when it is
   *       still anonymous and the token matches; on success return the now-owned row.
   *   <li>Anything else (no claim token, token mismatch, expired/cleared token, already owned by
   *       someone else) → 404. A not-owned row is deliberately indistinguishable from a missing
   *       one.
   * </ol>
   */
  private AnalysisRequestRow resolveOwnedOrClaimed(
      UUID analysisRequestId, UUID userId, String claimSessionToken) {
    Optional<AnalysisRequestRow> owned =
        analysisRepository.findByIdForOwner(
            analysisRequestId, InternalAuthPrincipal.ofUser(userId.toString()));
    if (owned.isPresent()) {
      return owned.get();
    }

    if (StringUtils.hasText(claimSessionToken)) {
      Optional<AnalysisRequestRow> claimed =
          analysisRepository.claimAnonymousAnalysis(analysisRequestId, claimSessionToken, userId);
      if (claimed.isPresent()) {
        return claimed.get();
      }
    }

    throw notFound();
  }

  private static UUID requireAuthenticatedUser(InternalAuthPrincipal principal) {
    if (principal == null || !principal.isAuthenticatedUser()) {
      throw new ApiErrorException(
          HttpStatus.UNAUTHORIZED,
          "internal_auth_invalid",
          "Login required.",
          "POST /expressions requires an authenticated user_id principal.",
          false);
    }
    try {
      return UUID.fromString(principal.userId());
    } catch (IllegalArgumentException e) {
      throw validationFailed("user_id claim must be a UUID.");
    }
  }

  private static UUID requireAnalysisRequestId(CreateExpressionRequest body) {
    if (body == null || body.analysisRequestId() == null) {
      throw validationFailed("analysis_request_id is required.");
    }
    return body.analysisRequestId();
  }

  private static int selectedVariantOrder(CreateExpressionRequest body) {
    Integer order = body == null ? null : body.selectedVariantOrder();
    if (order == null) {
      return DEFAULT_SELECTED_VARIANT_ORDER;
    }
    if (order < 1 || order > EXPECTED_VARIANT_COUNT) {
      throw validationFailed("selected_variant_order must be 1, 2, or 3.");
    }
    return order;
  }

  private static void validateIdempotencyKey(String header) {
    if (!StringUtils.hasText(header)) {
      throw validationFailed("Idempotency-Key header is required.");
    }
    try {
      UUID.fromString(header.trim());
    } catch (IllegalArgumentException e) {
      throw validationFailed("Idempotency-Key must be a UUID.");
    }
  }

  private static List<NewExpressionVariant> variantsFrom(JsonNode outputJson) {
    JsonNode expressions = outputJson == null ? null : outputJson.get("expressions");
    if (expressions == null
        || !expressions.isArray()
        || expressions.size() != EXPECTED_VARIANT_COUNT) {
      throw new ApiErrorException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "internal_server_error",
          "Analysis output cannot be saved.",
          "Stored analysis output_json must contain exactly 3 expressions.",
          true);
    }

    List<NewExpressionVariant> variants = new ArrayList<>(EXPECTED_VARIANT_COUNT);
    for (int i = 0; i < expressions.size(); i++) {
      JsonNode node = expressions.get(i);
      variants.add(
          new NewExpressionVariant(
              i + 1,
              textOrNull(node, "tone_label"),
              textOrNull(node, "english"),
              textOrNull(node, "ipa"),
              textOrNull(node, "korean_pronunciation"),
              textOrNull(node, "pronunciation_tip"),
              textOrNull(node, "cultural_tip")));
    }
    return variants;
  }

  private static String textOrNull(JsonNode node, String field) {
    return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
  }

  private static ApiErrorException validationFailed(String developerHint) {
    return new ApiErrorException(
        HttpStatus.BAD_REQUEST,
        "validation_failed",
        "Check the request and try again.",
        developerHint,
        false);
  }

  private static ApiErrorException notFound() {
    return new ApiErrorException(
        HttpStatus.NOT_FOUND,
        "not_found",
        "Analysis was not found.",
        "Analysis is missing or not owned by the caller.",
        false);
  }
}
