package com.phraselog.landing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.phraselog.landing.dto.LandingExampleResponse;
import com.phraselog.landing.repository.LandingExampleRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LandingExampleServiceTests {

  @Test
  void sampleRequestsThreeRandomActiveExamples() {
    LandingExampleRepository repository = mock(LandingExampleRepository.class);
    List<LandingExampleResponse> pool =
        List.of(
            new LandingExampleResponse(UUID.randomUUID(), "병원에서 증상을 정확하게 설명하고 싶어요."),
            new LandingExampleResponse(UUID.randomUUID(), "회의에서 다른 의견을 정중하게 말하고 싶어요."),
            new LandingExampleResponse(UUID.randomUUID(), "산 물건을 부담 없이 환불받고 싶어요."));
    when(repository.findRandomActive(3)).thenReturn(pool);
    LandingExampleService service = new LandingExampleService(repository);

    assertThat(service.sample()).isEqualTo(pool);
    verify(repository).findRandomActive(eq(3));
  }

  @Test
  void sampleReturnsEmptyListWhenPoolIsEmpty() {
    LandingExampleRepository repository = mock(LandingExampleRepository.class);
    when(repository.findRandomActive(3)).thenReturn(List.of());
    LandingExampleService service = new LandingExampleService(repository);

    assertThat(service.sample()).isEmpty();
  }
}
