package com.phraselog.user.config;

import com.phraselog.user.repository.JdbcUsageRepository;
import com.phraselog.user.repository.JdbcUserRepository;
import com.phraselog.user.repository.UnavailableUsageRepository;
import com.phraselog.user.repository.UnavailableUserRepository;
import com.phraselog.user.repository.UsageRepository;
import com.phraselog.user.repository.UserRepository;
import java.time.Clock;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the user/usage repositories while preserving the no-DB scaffold context (#52). */
@Configuration
public class UserConfiguration {

  private static final Logger log = LoggerFactory.getLogger(UserConfiguration.class);

  @Bean
  public UserRepository userRepository(ObjectProvider<DataSource> dataSourceProvider) {
    DataSource dataSource = dataSourceProvider.getIfAvailable();
    if (dataSource == null) {
      log.info("No DataSource present; /me is unavailable (no-DB context)");
      return new UnavailableUserRepository();
    }
    return new JdbcUserRepository(new JdbcTemplate(dataSource));
  }

  @Bean
  public UsageRepository usageRepository(ObjectProvider<DataSource> dataSourceProvider) {
    DataSource dataSource = dataSourceProvider.getIfAvailable();
    if (dataSource == null) {
      log.info("No DataSource present; GET /usage/today is unavailable (no-DB context)");
      return new UnavailableUsageRepository();
    }
    return new JdbcUsageRepository(new JdbcTemplate(dataSource));
  }

  /** UTC clock for the daily-usage boundary. Tests override with {@code Clock.fixed(...)}. */
  @Bean
  @ConditionalOnMissingBean(Clock.class)
  public Clock usageClock() {
    return Clock.systemUTC();
  }
}
