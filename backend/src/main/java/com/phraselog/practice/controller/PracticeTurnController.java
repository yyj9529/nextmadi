package com.phraselog.practice.controller;

import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.ApiErrorException;
import com.phraselog.common.web.ApiPaths;
import com.phraselog.practice.dto.SubmitPracticeTurnRequest;
import com.phraselog.practice.dto.SubmitPracticeTurnResponse;
import com.phraselog.practice.service.PracticeTurnService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** S12 turn endpoint (#60). */
@RestController
@RequestMapping(ApiPaths.V1 + "/practice/sessions/{session_id}/turns")
public class PracticeTurnController {

  private final PracticeTurnService service;

  public PracticeTurnController(PracticeTurnService service) {
    this.service = service;
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  public SubmitPracticeTurnResponse submitText(
      HttpServletRequest request,
      @PathVariable("session_id") String sessionId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestBody(required = false) SubmitPracticeTurnRequest body) {
    return service.submitTextTurn(principal(request), sessionId, body, idempotencyKey);
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public SubmitPracticeTurnResponse submitAudio(
      HttpServletRequest request,
      @PathVariable("session_id") String sessionId,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      @RequestPart(value = "audio", required = false) MultipartFile audio,
      @RequestParam(value = "text_content", required = false) String textContent) {
    if (audio == null || audio.isEmpty()) {
      return service.submitTextTurn(
          principal(request),
          sessionId,
          new SubmitPracticeTurnRequest(textContent),
          idempotencyKey);
    }
    return service.submitAudioTurn(principal(request), sessionId, audio, idempotencyKey);
  }

  private static InternalAuthPrincipal principal(HttpServletRequest request) {
    Object attribute = request.getAttribute(InternalAuthPrincipal.REQUEST_ATTRIBUTE);
    if (attribute instanceof InternalAuthPrincipal verified) {
      return verified;
    }
    throw new ApiErrorException(
        HttpStatus.UNAUTHORIZED,
        "internal_auth_invalid",
        "Login is required.",
        "No verified InternalAuthPrincipal on a protected practice turn route.",
        false);
  }
}
