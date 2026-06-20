package com.phraselog.auth.identity;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class OAuthIdentityConfiguration {

  private static final Logger log = LoggerFactory.getLogger(OAuthIdentityConfiguration.class);

  @Bean
  OAuthIdentityRepository oauthIdentityRepository(ObjectProvider<DataSource> dataSourceProvider) {
    DataSource dataSource = dataSourceProvider.getIfAvailable();
    if (dataSource == null) {
      log.info("No DataSource present; OAuth identity persistence is unavailable");
      return new UnavailableOAuthIdentityRepository();
    }
    return new JdbcOAuthIdentityRepository(new JdbcTemplate(dataSource));
  }
}
