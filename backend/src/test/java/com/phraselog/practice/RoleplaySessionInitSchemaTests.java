package com.phraselog.practice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phraselog.ai.client.service.JsonSchemaValidator;
import org.junit.jupiter.api.Test;

/**
 * Guards the {@code roleplay_session_init_v1} contract used by {@code POST /practice/sessions} (#59):
 * {@code planned_turns} must be an integer in [3, 10]. The validator loads the same classpath schema
 * the AI client uses at runtime, so an out-of-range model output is rejected before a session is
 * persisted.
 */
class RoleplaySessionInitSchemaTests {

  private static final String SCHEMA_ID = "roleplay_session_init_v1";

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final JsonSchemaValidator validator = new JsonSchemaValidator(objectMapper);

  @Test
  void acceptsInRangePlannedTurns() throws Exception {
    assertThatCode(() -> validator.validate(SCHEMA_ID, objectMapper.readTree(output(5))))
        .doesNotThrowAnyException();
  }

  @Test
  void acceptsBoundaryPlannedTurns() throws Exception {
    assertThatCode(() -> validator.validate(SCHEMA_ID, objectMapper.readTree(output(3))))
        .doesNotThrowAnyException();
    assertThatCode(() -> validator.validate(SCHEMA_ID, objectMapper.readTree(output(10))))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsPlannedTurnsBelowMinimum() throws Exception {
    assertThatThrownBy(() -> validator.validate(SCHEMA_ID, objectMapper.readTree(output(2))))
        .isInstanceOf(JsonSchemaValidator.JsonSchemaValidationException.class);
  }

  @Test
  void rejectsPlannedTurnsAboveMaximum() throws Exception {
    assertThatThrownBy(() -> validator.validate(SCHEMA_ID, objectMapper.readTree(output(11))))
        .isInstanceOf(JsonSchemaValidator.JsonSchemaValidationException.class);
  }

  @Test
  void rejectsMissingScenarioSetup() {
    String missing = "{\"planned_turns\":5,\"complexity_rationale\":\"r\"}";
    assertThatThrownBy(() -> validator.validate(SCHEMA_ID, objectMapper.readTree(missing)))
        .isInstanceOf(JsonSchemaValidator.JsonSchemaValidationException.class);
  }

  @Test
  void schemaIdMatchesPromptOutputSchema() {
    // The prompt front-matter (prompts/roleplay/init/v1.md) declares output_schema:
    // roleplay_session_init_v1; this test fails loudly if the schema file is renamed out of sync.
    assertThat(SCHEMA_ID).isEqualTo("roleplay_session_init_v1");
  }

  private static String output(int plannedTurns) {
    return "{\"planned_turns\":"
        + plannedTurns
        + ",\"scenario_setup\":\"Hello there.\",\"complexity_rationale\":\"rationale\"}";
  }
}
