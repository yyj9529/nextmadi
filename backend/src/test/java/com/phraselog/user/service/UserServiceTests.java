package com.phraselog.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.phraselog.auth.dto.InternalAuthPrincipal;
import com.phraselog.common.web.ApiErrorException;
import com.phraselog.user.dto.PatchMeRequest;
import com.phraselog.user.dto.UserResponse;
import com.phraselog.user.repository.UserRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class UserServiceTests {

  private UserRepository userRepository;
  private UserService service;

  @BeforeEach
  void setUp() {
    userRepository = mock(UserRepository.class);
    service = new UserService(userRepository);
  }

  @Test
  void getMeReturnsProfileForAuthenticatedUser() {
    UUID userId = UUID.randomUUID();
    UserResponse user = user(userId);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));

    assertThat(service.getMe(InternalAuthPrincipal.ofUser(userId.toString()))).isEqualTo(user);
  }

  @Test
  void getMeReturns404WhenUserMissing() {
    UUID userId = UUID.randomUUID();
    when(userRepository.findById(userId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getMe(InternalAuthPrincipal.ofUser(userId.toString())))
        .isInstanceOfSatisfying(
            ApiErrorException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  @Test
  void getMeRejectsAnonymousSessionPrincipalWith401() {
    assertThatThrownBy(() -> service.getMe(InternalAuthPrincipal.ofSession("anon-token")))
        .isInstanceOfSatisfying(
            ApiErrorException.class,
            e -> {
              assertThat(e.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
              assertThat(e.errorCode()).isEqualTo("internal_auth_invalid");
            });
  }

  @Test
  void patchRejectsDisplayNameOver100Chars() {
    UUID userId = UUID.randomUUID();
    String tooLong = "a".repeat(101);

    assertThatThrownBy(
            () ->
                service.updateMe(
                    InternalAuthPrincipal.ofUser(userId.toString()),
                    new PatchMeRequest(tooLong, null, null)))
        .isInstanceOfSatisfying(
            ApiErrorException.class,
            e -> {
              assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(e.errorCode()).isEqualTo("validation_failed");
            });
    verify(userRepository, never())
        .update(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
  }

  @Test
  void patchAcceptsDisplayNameExactly100Chars() {
    UUID userId = UUID.randomUUID();
    String boundary = "a".repeat(100);
    UserResponse updated = user(userId);
    when(userRepository.update(eq(userId), eq(boundary), isNull(), eq(false)))
        .thenReturn(Optional.of(updated));

    assertThat(
            service.updateMe(
                InternalAuthPrincipal.ofUser(userId.toString()),
                new PatchMeRequest(boundary, null, null)))
        .isEqualTo(updated);
  }

  @Test
  void patchRejectsOnboardedFalse() {
    UUID userId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                service.updateMe(
                    InternalAuthPrincipal.ofUser(userId.toString()),
                    new PatchMeRequest(null, null, false)))
        .isInstanceOfSatisfying(
            ApiErrorException.class,
            e -> {
              assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(e.errorCode()).isEqualTo("validation_failed");
            });
    verify(userRepository, never())
        .update(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
  }

  @Test
  void patchSetsOnboardedTrue() {
    UUID userId = UUID.randomUUID();
    UserResponse updated = user(userId);
    when(userRepository.update(eq(userId), isNull(), isNull(), eq(true)))
        .thenReturn(Optional.of(updated));

    service.updateMe(
        InternalAuthPrincipal.ofUser(userId.toString()), new PatchMeRequest(null, null, true));

    verify(userRepository).update(eq(userId), isNull(), isNull(), eq(true));
  }

  @Test
  void patchRejectsUnknownCoachId() {
    UUID userId = UUID.randomUUID();
    UUID coachId = UUID.randomUUID();
    when(userRepository.coachExists(coachId)).thenReturn(false);

    assertThatThrownBy(
            () ->
                service.updateMe(
                    InternalAuthPrincipal.ofUser(userId.toString()),
                    new PatchMeRequest(null, coachId, null)))
        .isInstanceOfSatisfying(
            ApiErrorException.class,
            e -> {
              assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
              assertThat(e.errorCode()).isEqualTo("validation_failed");
            });
    verify(userRepository, never())
        .update(any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
  }

  @Test
  void patchSavesValidCoachId() {
    UUID userId = UUID.randomUUID();
    UUID coachId = UUID.randomUUID();
    UserResponse updated = user(userId);
    when(userRepository.coachExists(coachId)).thenReturn(true);
    when(userRepository.update(eq(userId), isNull(), eq(coachId), eq(false)))
        .thenReturn(Optional.of(updated));

    assertThat(
            service.updateMe(
                InternalAuthPrincipal.ofUser(userId.toString()),
                new PatchMeRequest(null, coachId, null)))
        .isEqualTo(updated);
  }

  @Test
  void patchWithNullBodyIsNoOpAndReturnsCurrentUser() {
    UUID userId = UUID.randomUUID();
    UserResponse current = user(userId);
    when(userRepository.update(eq(userId), isNull(), isNull(), eq(false)))
        .thenReturn(Optional.of(current));

    assertThat(service.updateMe(InternalAuthPrincipal.ofUser(userId.toString()), null))
        .isEqualTo(current);
  }

  @Test
  void patchReturns404WhenUserMissing() {
    UUID userId = UUID.randomUUID();
    when(userRepository.update(eq(userId), isNull(), isNull(), eq(true)))
        .thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.updateMe(
                    InternalAuthPrincipal.ofUser(userId.toString()),
                    new PatchMeRequest(null, null, true)))
        .isInstanceOfSatisfying(
            ApiErrorException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.NOT_FOUND));
  }

  private static UserResponse user(UUID userId) {
    return new UserResponse(
        userId,
        "user@example.com",
        "우주",
        null,
        false,
        OffsetDateTime.parse("2026-06-01T00:00:00Z"),
        null);
  }
}
