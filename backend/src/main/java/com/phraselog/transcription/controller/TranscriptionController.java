package com.phraselog.transcription.controller;

import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.ApiErrorException;
import com.phraselog.common.web.ApiPaths;
import com.phraselog.transcription.dto.TranscriptionResponse;
import com.phraselog.transcription.service.TranscriptionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping(ApiPaths.V1 + "/transcriptions")
public class TranscriptionController {

  private final TranscriptionService service;

  public TranscriptionController(TranscriptionService service) {
    this.service = service;
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public TranscriptionResponse create(
      HttpServletRequest request,
      @RequestPart(value = "audio", required = false) MultipartFile audio) {
    return service.transcribe(principal(request), audio);
  }

  private static InternalAuthPrincipal principal(HttpServletRequest request) {
    Object attribute = request.getAttribute(InternalAuthPrincipal.REQUEST_ATTRIBUTE);
    if (attribute instanceof InternalAuthPrincipal verified) {
      return verified;
    }
    throw new ApiErrorException(
        HttpStatus.UNAUTHORIZED,
        "internal_auth_invalid",
        "濡쒓렇?몄씠 ?꾩슂?댁슂.",
        "No verified InternalAuthPrincipal on a protected transcription route.",
        false);
  }
}
