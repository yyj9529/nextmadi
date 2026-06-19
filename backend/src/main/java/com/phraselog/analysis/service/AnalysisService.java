package com.phraselog.analysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.phraselog.ai.client.service.AnthropicService;
import com.phraselog.ai.logging.dto.AiFeature;
import com.phraselog.ai.prompt.dto.PromptDefinition;
import com.phraselog.ai.prompt.service.PromptLoader;
import com.phraselog.analysis.dto.AnalysisRequestRow;
import com.phraselog.analysis.dto.AnalysisResponse;
import com.phraselog.analysis.dto.CreateAnalysisRequest;
import com.phraselog.analysis.dto.ExpressionVariantDto;
import com.phraselog.analysis.dto.NewAnalysis;
import com.phraselog.analysis.repository.AnalysisRepository;
import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.ApiErrorException;
import com.phraselog.usage.service.AnonymousAnalysisUsageService;
import com.phraselog.usage.service.ClientIpResolver;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Orchestrates the S07 analysis flow (#39): validate, honor idempotency, apply the anonymous rate
 * limit, call the S07 LLM pipeline, persist, and map to the {@code AnalysisRequest} response.
 *
 * <p>Ordering matters: the idempotency lookup runs <em>before</em> the rate-limit reservation so a
 * retry from the same caller neither consumes a second anonymous slot nor re-bills the LLM. The
 * {@link AnonymousAnalysisUsageService} reserves a slot before the protected work and releases it
 * if the work throws (provider/schema failure), so only successful analyses stay counted.
 *
 * <p>{@code AnonymousAnalysisUsageService} (#34) is DB-conditional and absent in the no-DB scaffold
 * context, so it is injected via {@link ObjectProvider} and resolved lazily at request time; that
 * keeps this component-scanned {@code @Service} constructable even when no {@code DataSource} is
 * configured (the analysis routes are never exercised there).
 */
@Service
public class AnalysisService {

  private static final String PROMPT_PATH = "s07";
  private static final int PROMPT_VERSION = 1;
  private static final int MAX_INPUT_LENGTH = 500;

  private final AnalysisRepository repository;
  private final AnthropicService anthropicService;
  private final PromptLoader promptLoader;
  private final ObjectProvider<AnonymousAnalysisUsageService> usageServiceProvider;
  private final ClientIpResolver clientIpResolver;

  public AnalysisService(
      AnalysisRepository repository,
      AnthropicService anthropicService,
      PromptLoader promptLoader,
      ObjectProvider<AnonymousAnalysisUsageService> usageServiceProvider,
      ClientIpResolver clientIpResolver) {
    this.repository = repository;
    this.anthropicService = anthropicService;
    this.promptLoader = promptLoader;
    this.usageServiceProvider = usageServiceProvider;
    this.clientIpResolver = clientIpResolver;
  }

  /** Handles {@code POST /analysis}. */
  public AnalysisResponse create(
      InternalAuthPrincipal principal,
      CreateAnalysisRequest body,
      String idempotencyKeyHeader,
      String clientIpHeader) {

    String inputText = validatedInput(body);
    UUID idempotencyKey = parseIdempotencyKey(idempotencyKeyHeader);

    Optional<AnalysisRequestRow> existing =
        repository.findByCallerAndKey(principal, idempotencyKey);
    if (existing.isPresent()) {
      return toResponse(existing.get());
    }

    boolean anonymous = !principal.isAuthenticatedUser();
    UUID userId = anonymous ? null : UUID.fromString(principal.userId());
    String sessionToken = anonymous ? principal.sessionToken() : null;
    // Resolve (and validate) the anonymous client IP before any reservation/LLM call. Invalid IP
    // surfaces as a 400 here. Authenticated calls do not carry or store an IP in v1.
    String ipAddress = anonymous ? clientIpResolver.resolveRequired(clientIpHeader) : null;

    AnalysisRequestRow row =
        usageServiceProvider
            .getObject()
            .withAnonymousAnalysisLimit(
                principal,
                clientIpHeader,
                () ->
                    runAnalysis(
                        principal, inputText, idempotencyKey, userId, sessionToken, ipAddress));

    return toResponse(row);
  }

  /** Handles {@code GET /analysis/{id}}: owner-only, 404 for missing/not-owned/malformed id. */
  public AnalysisResponse get(InternalAuthPrincipal principal, String analysisRequestId) {
    UUID id = parseIdOrNotFound(analysisRequestId);
    return repository
        .findByIdForOwner(id, principal)
        .map(this::toResponse)
        .orElseThrow(AnalysisService::notFound);
  }

  private AnalysisRequestRow runAnalysis(
      InternalAuthPrincipal principal,
      String inputText,
      UUID idempotencyKey,
      UUID userId,
      String sessionToken,
      String ipAddress) {

    PromptDefinition prompt = promptLoader.load(PROMPT_PATH, PROMPT_VERSION);
    UUID correlationId = UUID.randomUUID();

    // callClaude validates s07_analysis_v1 (exactly 3 expressions) and logs success/failure to
    // ai_request_logs internally; it throws ApiErrorException on provider/schema failure.
    JsonNode output =
        anthropicService.callClaude(
            AiFeature.S07_ANALYSIS, prompt, inputText, userId, correlationId);

    UUID aiRequestLogId = repository.findLogIdByCorrelation(correlationId).orElse(null);

    NewAnalysis newAnalysis =
        new NewAnalysis(
            userId,
            sessionToken,
            ipAddress,
            inputText,
            output,
            prompt.promptVersion(),
            aiRequestLogId,
            idempotencyKey);

    try {
      return repository.insert(newAnalysis);
    } catch (DuplicateKeyException race) {
      // A concurrent retry under the same idempotency key won the insert. Return its row.
      return repository.findByCallerAndKey(principal, idempotencyKey).orElseThrow(() -> race);
    }
  }

  private AnalysisResponse toResponse(AnalysisRequestRow row) {
    JsonNode expressions = row.outputJson().get("expressions");
    if (expressions == null || !expressions.isArray()) {
      throw new ApiErrorException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "internal_server_error",
          "문제가 발생했어요. 잠시 후 다시 시도해 주세요.",
          "Stored analysis output_json has no expressions array.",
          true);
    }

    List<ExpressionVariantDto> variants = new ArrayList<>(expressions.size());
    for (int i = 0; i < expressions.size(); i++) {
      JsonNode e = expressions.get(i);
      int order = i + 1;
      UUID variantId =
          UUID.nameUUIDFromBytes((row.id() + ":" + order).getBytes(StandardCharsets.UTF_8));
      variants.add(
          new ExpressionVariantDto(
              variantId,
              order,
              textOrNull(e, "tone_label"),
              textOrNull(e, "english"),
              textOrNull(e, "ipa"),
              textOrNull(e, "korean_pronunciation"),
              textOrNull(e, "pronunciation_tip"),
              textOrNull(e, "cultural_tip"),
              null));
    }

    return new AnalysisResponse(
        row.id(), row.inputText(), variants, row.promptVersion(), row.createdAt());
  }

  private static String textOrNull(JsonNode node, String field) {
    return node.hasNonNull(field) ? node.get(field).asText() : null;
  }

  private String validatedInput(CreateAnalysisRequest body) {
    String inputText = body == null ? null : body.inputText();
    if (!StringUtils.hasText(inputText)) {
      throw validationFailed("input_text is required.");
    }
    if (inputText.length() > MAX_INPUT_LENGTH) {
      throw validationFailed("input_text must be at most " + MAX_INPUT_LENGTH + " characters.");
    }
    return inputText;
  }

  private UUID parseIdempotencyKey(String header) {
    if (!StringUtils.hasText(header)) {
      throw validationFailed("Idempotency-Key header is required.");
    }
    try {
      return UUID.fromString(header.trim());
    } catch (IllegalArgumentException e) {
      throw validationFailed("Idempotency-Key must be a UUID.");
    }
  }

  private UUID parseIdOrNotFound(String analysisRequestId) {
    try {
      return UUID.fromString(analysisRequestId);
    } catch (IllegalArgumentException | NullPointerException e) {
      // A malformed id cannot identify any row; treat as not found per the GET contract.
      throw notFound();
    }
  }

  private static ApiErrorException validationFailed(String developerHint) {
    return new ApiErrorException(
        HttpStatus.BAD_REQUEST, "validation_failed", "입력값을 다시 확인해 주세요.", developerHint, false);
  }

  private static ApiErrorException notFound() {
    return new ApiErrorException(
        HttpStatus.NOT_FOUND,
        "not_found",
        "찾을 수 없는 결과예요.",
        "Analysis is missing or not owned by the caller.",
        false);
  }
}
