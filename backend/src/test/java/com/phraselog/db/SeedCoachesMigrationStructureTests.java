package com.phraselog.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Pure file-level checks on the V003 coach seed (#52) so the seed content is verified even when
 * Docker (and thus the Flyway/Testcontainers migration test) is unavailable.
 */
class SeedCoachesMigrationStructureTests {

  @Test
  void seedMigrationInsertsThreeCoachesWithChosenVoices() throws Exception {
    String sql =
        new String(
            new ClassPathResource("db/migration/V003__seed_coaches.sql")
                .getInputStream()
                .readAllBytes(),
            StandardCharsets.UTF_8);

    assertThat(sql).contains("INSERT INTO coach_profiles");
    assertThat(sql).contains("'mia'", "'david'", "'sarah'");
    assertThat(sql).contains("'shimmer'", "'onyx'", "'nova'");
    assertThat(sql)
        .contains(
            "prompts/roleplay/mia/v1.md",
            "prompts/roleplay/david/v1.md",
            "prompts/roleplay/sarah/v1.md");
    // Idempotent re-run guard so a redeploy does not duplicate seed rows.
    assertThat(sql).contains("ON CONFLICT (slug) DO NOTHING");
  }
}
