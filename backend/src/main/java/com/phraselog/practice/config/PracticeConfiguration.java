package com.phraselog.practice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.phraselog.practice.repository.JdbcPracticeTurnRepository;
import com.phraselog.practice.repository.PracticeTurnRepository;
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

@Configuration
public class PracticeConfiguration {

  private static final Logger log = LoggerFactory.getLogger(PracticeConfiguration.class);

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
