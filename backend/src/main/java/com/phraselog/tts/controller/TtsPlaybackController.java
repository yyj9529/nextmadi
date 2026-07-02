package com.phraselog.tts.controller;

import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.ApiErrorException;
import com.phraselog.common.web.ApiPaths;
import com.phraselog.tts.dto.TtsPlaybackRequest;
import com.phraselog.tts.dto.TtsPlaybackResponse;
import com.phraselog.tts.service.TtsPlaybackResult;
import com.phraselog.tts.service.TtsPlaybackService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** TTS 합성/재생 엔드포인트({@code POST /api/v1/tts/playback}, #30). */
@RestController
@RequestMapping(ApiPaths.V1 + "/tts")
public class TtsPlaybackController {

  private final TtsPlaybackService service;

  public TtsPlaybackController(TtsPlaybackService service) {
    this.service = service;
  }

  @PostMapping(path = "/playback", consumes = MediaType.APPLICATION_JSON_VALUE)
  public TtsPlaybackResponse playback(
      HttpServletRequest request, @RequestBody TtsPlaybackRequest body) {
    InternalAuthPrincipal principal = principal(request);
    UUID userId = principal.isAuthenticatedUser() ? UUID.fromString(principal.userId()) : null;
    UUID variantId = parseVariantId(body == null ? null : body.expressionVariantId());

    TtsPlaybackResult result =
        service.playback(
            userId,
            body == null ? null : body.text(),
            body == null ? null : body.voiceId(),
            variantId,
            UUID.randomUUID());
    return new TtsPlaybackResponse(result.audioUrl(), result.durationMs(), result.cacheStatus());
  }

  private static UUID parseVariantId(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    try {
      return UUID.fromString(value.trim());
    } catch (IllegalArgumentException e) {
      throw new ApiErrorException(
          HttpStatus.BAD_REQUEST,
          "validation_failed",
          "입력값을 다시 확인해 주세요.",
          "expression_variant_id must be a UUID.",
          false);
    }
  }

  private static InternalAuthPrincipal principal(HttpServletRequest request) {
    Object attribute = request.getAttribute(InternalAuthPrincipal.REQUEST_ATTRIBUTE);
    if (attribute instanceof InternalAuthPrincipal verified) {
      return verified;
    }
    throw new ApiErrorException(
        HttpStatus.UNAUTHORIZED,
        "internal_auth_invalid",
        "로그인이 필요해요.",
        "No verified InternalAuthPrincipal on a protected tts route.",
        false);
  }
}
