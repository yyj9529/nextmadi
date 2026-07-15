package com.phraselog.landing.config;

import com.phraselog.landing.repository.JdbcLandingExampleRepository;
import com.phraselog.landing.repository.LandingExampleRepository;
import com.phraselog.landing.repository.UnavailableLandingExampleRepository;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the landing example repository while preserving the no-DB scaffold context. */
@Configuration
public class LandingConfiguration {

  private static final Logger log = LoggerFactory.getLogger(LandingConfiguration.class);

  @Bean
  public LandingExampleRepository landingExampleRepository(
      ObjectProvider<DataSource> dataSourceProvider) {
    DataSource dataSource = dataSourceProvider.getIfAvailable();
    if (dataSource == null) {
      log.info("No DataSource present; GET /landing/examples is unavailable (no-DB context)");
      return new UnavailableLandingExampleRepository();
    }
    return new JdbcLandingExampleRepository(new JdbcTemplate(dataSource));
  }
}
