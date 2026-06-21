package com.phraselog.coach.config;

import com.phraselog.coach.repository.CoachRepository;
import com.phraselog.coach.repository.JdbcCoachRepository;
import com.phraselog.coach.repository.UnavailableCoachRepository;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the coach repository while preserving the no-DB scaffold context. */
@Configuration
public class CoachConfiguration {

  private static final Logger log = LoggerFactory.getLogger(CoachConfiguration.class);

  @Bean
  public CoachRepository coachRepository(ObjectProvider<DataSource> dataSourceProvider) {
    DataSource dataSource = dataSourceProvider.getIfAvailable();
    if (dataSource == null) {
      log.info("No DataSource present; GET /coaches is unavailable (no-DB context)");
      return new UnavailableCoachRepository();
    }
    return new JdbcCoachRepository(new JdbcTemplate(dataSource));
  }
}
