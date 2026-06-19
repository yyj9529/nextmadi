package com.phraselog.usage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class AnonymousAnalysisUsageConfiguration {

  @Bean
  @ConditionalOnBean(JdbcTemplate.class)
  AnonymousAnalysisUsageRepository anonymousAnalysisUsageRepository(JdbcTemplate jdbcTemplate) {
    return new JdbcAnonymousAnalysisUsageRepository(jdbcTemplate);
  }

  @Bean
  @ConditionalOnBean(JdbcTemplate.class)
  AnonymousAnalysisUsageService anonymousAnalysisUsageService(
      AnonymousAnalysisUsageRepository repository, ClientIpResolver clientIpResolver) {
    return new AnonymousAnalysisUsageService(repository, clientIpResolver);
  }
}
