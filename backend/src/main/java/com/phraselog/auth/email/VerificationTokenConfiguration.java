package com.phraselog.auth.email;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

// ObjectProvider, not @ConditionalOnBean: user configuration is evaluated before the JdbcTemplate
// auto-configuration that would supply the bean, so the condition is always false and the service
// silently never exists (docs/solutions/spring-conditional-bean-ordering.md).
@Configuration
public class VerificationTokenConfiguration {

  private static final Logger log = LoggerFactory.getLogger(VerificationTokenConfiguration.class);

  @Bean
  VerificationTokenRepository verificationTokenRepository(
      ObjectProvider<DataSource> dataSourceProvider) {
    DataSource dataSource = dataSourceProvider.getIfAvailable();
    if (dataSource == null) {
      log.info("No DataSource present; email verification token storage is unavailable");
      return new UnavailableVerificationTokenRepository();
    }
    return new JdbcVerificationTokenRepository(new JdbcTemplate(dataSource));
  }
}
