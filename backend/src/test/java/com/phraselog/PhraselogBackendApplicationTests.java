package com.phraselog;

import static org.assertj.core.api.Assertions.assertThat;

import com.phraselog.common.web.ApiPaths;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
    })
class PhraselogBackendApplicationTests {

  @Autowired private TestRestTemplate restTemplate;

  @Autowired private Environment environment;

  @Test
  void contextLoads() {}

  @Test
  void apiBasePathUsesVersionedConvention() {
    assertThat(environment.getProperty("phraselog.api.base-path")).isEqualTo(ApiPaths.V1);
  }

  @Test
  void actuatorHealthIsAvailableAtRootPath() {
    ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }
}
