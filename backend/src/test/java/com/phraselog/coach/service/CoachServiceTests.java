package com.phraselog.coach.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.coach.dto.CoachResponse;
import com.phraselog.coach.repository.CoachRepository;
import com.phraselog.common.web.ApiErrorException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class CoachServiceTests {

  @Test
  void listReturnsCatalogForAuthenticatedUser() {
    CoachRepository repository = mock(CoachRepository.class);
    List<CoachResponse> coaches =
        List.of(
            new CoachResponse(UUID.randomUUID(), "mia", "Mia", "친절한 코치.", "shimmer"),
            new CoachResponse(UUID.randomUUID(), "david", "David", "근엄한 코치.", "onyx"),
            new CoachResponse(UUID.randomUUID(), "sarah", "Sarah", "프로페셔널 코치.", "nova"));
    when(repository.findAll()).thenReturn(coaches);
    CoachService service = new CoachService(repository);

    assertThat(service.list(InternalAuthPrincipal.ofUser(UUID.randomUUID().toString())))
        .isEqualTo(coaches);
  }

  @Test
  void listRejectsAnonymousSessionPrincipalWith401() {
    CoachService service = new CoachService(mock(CoachRepository.class));

    assertThatThrownBy(() -> service.list(InternalAuthPrincipal.ofSession("anon-token")))
        .isInstanceOfSatisfying(
            ApiErrorException.class,
            e -> {
              assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
              assertThat(e.errorCode()).isEqualTo("internal_auth_invalid");
            });
  }
}
