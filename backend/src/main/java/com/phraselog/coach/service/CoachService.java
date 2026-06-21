package com.phraselog.coach.service;

import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.coach.dto.CoachResponse;
import com.phraselog.coach.repository.CoachRepository;
import com.phraselog.common.web.ApiErrorException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Backend core for {@code GET /coaches} (#52). The catalog is seed data, but the endpoint stays
 * behind {@code X-Internal-Auth} (no {@code security: []} in openapi) and is only used by the
 * authenticated S03b/S11 screens, so an authenticated user principal is required.
 */
@Service
public class CoachService {

  private final CoachRepository coachRepository;

  public CoachService(CoachRepository coachRepository) {
    this.coachRepository = coachRepository;
  }

  public List<CoachResponse> list(InternalAuthPrincipal principal) {
    requireAuthenticatedUser(principal);
    return coachRepository.findAll();
  }

  private static void requireAuthenticatedUser(InternalAuthPrincipal principal) {
    if (principal == null || !principal.isAuthenticatedUser()) {
      throw new ApiErrorException(
          HttpStatus.UNAUTHORIZED,
          "internal_auth_invalid",
          "로그인이 필요해요.",
          "GET /coaches requires an authenticated user_id principal.",
          false);
    }
  }
}
