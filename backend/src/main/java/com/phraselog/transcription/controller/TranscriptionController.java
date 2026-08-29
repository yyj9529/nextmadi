package com.phraselog.transcription.controller;

import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.ApiErrorException;
import com.phraselog.common.web.ApiPaths;
import com.phraselog.transcription.dto.TranscriptionResponse;
import com.phraselog.transcription.service.TranscriptionService;
import com.phraselog.usage.service.AnonymousTranscriptionUsageService;
import com.phraselog.usage.service.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping(ApiPaths.V1 + "/transcriptions")
public class TranscriptionController {

  private final TranscriptionService service;
  private final ObjectProvider<AnonymousTranscriptionUsageService> usageServiceProvider;

  public TranscriptionController(
      TranscriptionService service,
      ObjectProvider<AnonymousTranscriptionUsageService> usageServiceProvider) {
    this.service = service;
    this.usageServiceProvider = usageServiceProvider;
  }

  /**
   * 한도는 서비스가 아니라 이 엔드포인트에 건다. {@link TranscriptionService} 는 롤플레이 턴 ({@code PracticeTurnService})도
   * 함께 쓰는 공용 기능이고, 롤플레이는 자체 하루 한도를 따로 갖고 있다. 정책은 공개 엔드포인트가 소유하는 편이 맞다.
   */
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public TranscriptionResponse create(
      HttpServletRequest request,
      @RequestHeader(value = ClientIpResolver.HEADER, required = false) String clientIp,
      @RequestPart(value = "audio", required = false) MultipartFile audio) {
    InternalAuthPrincipal principal = principal(request);
    return usageServiceProvider
        .getObject()
        .withAnonymousTranscriptionLimit(
            principal, clientIp, () -> service.transcribe(principal, audio));
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
        "No verified InternalAuthPrincipal on a protected transcription route.",
        false);
  }
}
