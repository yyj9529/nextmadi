package com.phraselog.practice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phraselog.practice.repository.JdbcPracticeRepository;
import com.phraselog.practice.repository.JdbcPracticeTurnRepository;
import com.phraselog.practice.repository.PracticeRepository;
import com.phraselog.practice.repository.PracticeTurnRepository;
import com.phraselog.practice.repository.UnavailablePracticeRepository;
import com.phraselog.practice.repository.UnavailablePracticeTurnRepository;
import com.phraselog.practice.service.NoopPracticeAudioService;
import com.phraselog.practice.service.PracticeAudioService;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires the practice repositories according to whether a {@code DataSource} is available, using the
 * same {@link ObjectProvider} pattern as {@link
 * com.phraselog.analysis.config.AnalysisConfiguration} so the no-DB scaffold context still loads.
 *
 * <p>{@link com.phraselog.practice.service.PracticeSessionService} (#59) and {@link
 * com.phraselog.practice.service.PracticeTurnService} (#60), along with their controllers, are
 * ordinary component-scanned beans; the repositories are the pieces that need a database, so they
 * are the ones gated here. {@link PracticeRepository} backs session start/retrieval; {@link
 * PracticeTurnRepository} backs the turn pipeline; {@link PracticeAudioService} is a no-op until
 * the TTS backend (#30) lands.
 */
@Configuration
public class PracticeConfiguration {

  private static final Logger log = LoggerFactory.getLogger(PracticeConfiguration.class);

  @Bean
  public PracticeRepository practiceRepository(ObjectProvider<DataSource> dataSourceProvider) {
    DataSource dataSource = dataSourceProvider.getIfAvailable();
    if (dataSource == null) {
      log.info(
          "No DataSource present; POST/GET /practice/sessions are unavailable (no-DB context)");
      return new UnavailablePracticeRepository();
    }
    return new JdbcPracticeRepository(new JdbcTemplate(dataSource));
  }

  @Bean
  public PracticeTurnRepository practiceTurnRepository(
      ObjectProvider<DataSource> dataSourceProvider, ObjectMapper objectMapper) {
    DataSource dataSource = dataSourceProvider.getIfAvailable();
    if (dataSource == null) {
      log.info("No DataSource present; practice turn routes are unavailable (no-DB context)");
      return new UnavailablePracticeTurnRepository();
    }
    return new JdbcPracticeTurnRepository(
        new JdbcTemplate(dataSource),
        new TransactionTemplate(
            new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource)),
        objectMapper);
  }

  @Bean
  public PracticeAudioService practiceAudioService() {
    return new NoopPracticeAudioService();
  }
}
