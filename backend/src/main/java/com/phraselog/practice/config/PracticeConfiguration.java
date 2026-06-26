package com.phraselog.practice.config;

import com.phraselog.practice.repository.JdbcPracticeRepository;
import com.phraselog.practice.repository.PracticeRepository;
import com.phraselog.practice.repository.UnavailablePracticeRepository;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the {@link PracticeRepository} according to whether a {@code DataSource} is available, using
 * the same {@link ObjectProvider} pattern as {@link
 * com.phraselog.analysis.config.AnalysisConfiguration} so the no-DB scaffold context still loads.
 *
 * <p>{@link com.phraselog.practice.service.PracticeSessionService} and {@link
 * com.phraselog.practice.controller.PracticeController} are ordinary component-scanned beans; the
 * repository is the only piece that needs a database, so it is the only one gated here.
 */
@Configuration
public class PracticeConfiguration {

  private static final Logger log = LoggerFactory.getLogger(PracticeConfiguration.class);

  @Bean
  public PracticeRepository practiceRepository(ObjectProvider<DataSource> dataSourceProvider) {
    DataSource dataSource = dataSourceProvider.getIfAvailable();
    if (dataSource == null) {
      log.info("No DataSource present; POST/GET /practice/sessions are unavailable (no-DB context)");
      return new UnavailablePracticeRepository();
    }
    return new JdbcPracticeRepository(new JdbcTemplate(dataSource));
  }
}
