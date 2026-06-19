package com.phraselog.analysis.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phraselog.analysis.controller.AnalysisController;
import com.phraselog.analysis.repository.AnalysisRepository;
import com.phraselog.analysis.repository.JdbcAnalysisRepository;
import com.phraselog.analysis.repository.UnavailableAnalysisRepository;
import com.phraselog.analysis.service.AnalysisService;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the {@link AnalysisRepository} according to whether a {@code DataSource} is available,
 * using the same {@link ObjectProvider} pattern as {@link
 * com.phraselog.ai.logging.config.AiLoggingConfiguration} so the no-DB scaffold context (which
 * excludes {@code DataSourceAutoConfiguration}) still loads.
 *
 * <p>{@link AnalysisService} and {@link AnalysisController} are ordinary component-scanned beans;
 * the repository is the only piece that needs a database, so it is the only one gated here.
 */
@Configuration
public class AnalysisConfiguration {

  private static final Logger log = LoggerFactory.getLogger(AnalysisConfiguration.class);

  @Bean
  public AnalysisRepository analysisRepository(
      ObjectProvider<DataSource> dataSourceProvider, ObjectMapper objectMapper) {
    DataSource dataSource = dataSourceProvider.getIfAvailable();
    if (dataSource == null) {
      log.info("No DataSource present; POST/GET /analysis are unavailable (no-DB context)");
      return new UnavailableAnalysisRepository();
    }
    return new JdbcAnalysisRepository(new JdbcTemplate(dataSource), objectMapper);
  }
}
