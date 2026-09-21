package com.phraselog.expression.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.phraselog.analysis.dto.AnalysisRequestRow;
import com.phraselog.analysis.repository.AnalysisRepository;
import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.ApiErrorException;
import com.phraselog.expression.dto.CreateExpressionRequest;
import com.phraselog.expression.dto.ExpressionListItem;
import com.phraselog.expression.dto.ExpressionListResponse;
import com.phraselog.expression.dto.ExpressionResponse;
import com.phraselog.expression.dto.NewExpression;
import com.phraselog.expression.dto.NewExpressionVariant;
import com.phraselog.expression.dto.SaveExpressionResult;
import com.phraselog.expression.repository.ExpressionRepository;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
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
  private static final int DEFAULT_LIMIT = 20;
  private static final int MAX_LIMIT = 100;
  private static final int MAX_QUERY_LENGTH = 100;
  private static final String CURSOR_SEPARATOR = "|";

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

    if (!analysis.outputJson().path("result_type").asText("expressions").equals("expressions")) {
      throw new ApiErrorException(
          HttpStatus.BAD_REQUEST,
          "analysis_not_saveable",
          "표현이 완성된 뒤 저장할 수 있어요.",
          "Clarification and word lookup results cannot be saved as expressions.",
          false);
    }

    NewExpression command =
        new NewExpression(
            userId,
            analysis.id(),
            analysis.inputText(),
            selectedVariantOrder,
            variantsFrom(analysis.outputJson()));

    return new SaveExpressionResult(expressionRepository.createFromAnalysis(command), false);
  }

  /** S08 library list (#45): cursor pagination plus optional Korean/English keyword search. */
  @Transactional(readOnly = true)
  public ExpressionListResponse list(
      InternalAuthPrincipal principal, String q, String cursor, Integer limit) {
    UUID userId = requireAuthenticatedUser(principal);
    int pageSize = clampLimit(limit);
    String keyword = normalizeQuery(q);
    Cursor decoded = decodeCursor(cursor);

    List<ExpressionListItem> items =
        expressionRepository.list(
            userId,
            keyword,
            decoded == null ? null : decoded.createdAt(),
            decoded == null ? null : decoded.id(),
            pageSize);

    String nextCursor = null;
    if (items.size() == pageSize) {
      ExpressionListItem last = items.get(items.size() - 1);
      nextCursor = encodeCursor(last.createdAt(), last.id());
    }
    return new ExpressionListResponse(items, nextCursor);
  }

  /** S09 detail (#45): full expression with all 3 variants; 404 if missing or not owned. */
  @Transactional(readOnly = true)
  public ExpressionResponse get(InternalAuthPrincipal principal, UUID expressionId) {
    UUID userId = requireAuthenticatedUser(principal);
    return expressionRepository
        .findByIdForUser(expressionId, userId)
        .orElseThrow(ExpressionService::expressionNotFound);
  }

  /** S09 soft delete (#45): sets {@code deleted_at}; 404 if missing, not owned, or already gone. */
  @Transactional
  public void delete(InternalAuthPrincipal principal, UUID expressionId) {
    UUID userId = requireAuthenticatedUser(principal);
    if (!expressionRepository.softDelete(expressionId, userId)) {
      throw expressionNotFound();
    }
  }

  private static int clampLimit(Integer limit) {
    if (limit == null || limit < 1) {
      return DEFAULT_LIMIT;
    }
    return Math.min(limit, MAX_LIMIT);
  }

  private static String normalizeQuery(String q) {
    if (!StringUtils.hasText(q)) {
      return null;
    }
    String trimmed = q.trim();
    return trimmed.length() > MAX_QUERY_LENGTH ? trimmed.substring(0, MAX_QUERY_LENGTH) : trimmed;
  }

  private static String encodeCursor(OffsetDateTime createdAt, UUID id) {
    String raw = createdAt.toString() + CURSOR_SEPARATOR + id;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  private static Cursor decodeCursor(String cursor) {
    if (!StringUtils.hasText(cursor)) {
      return null;
    }
    try {
      String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      int sep = raw.lastIndexOf(CURSOR_SEPARATOR);
      if (sep <= 0) {
        throw validationFailed("cursor is malformed.");
      }
      OffsetDateTime createdAt = OffsetDateTime.parse(raw.substring(0, sep));
      UUID id = UUID.fromString(raw.substring(sep + 1));
      return new Cursor(createdAt, id);
    } catch (IllegalArgumentException | DateTimeParseException e) {
      throw validationFailed("cursor is invalid.");
    }
  }

  private record Cursor(OffsetDateTime createdAt, UUID id) {}

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

  private static ApiErrorException expressionNotFound() {
    return new ApiErrorException(
        HttpStatus.NOT_FOUND,
        "not_found",
        "Expression was not found.",
        "Expression is missing, soft-deleted, or not owned by the caller.",
        false);
  }
}
