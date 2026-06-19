package com.phraselog.expression.config;

import com.phraselog.expression.repository.ExpressionRepository;
import com.phraselog.expression.repository.JdbcExpressionRepository;
import com.phraselog.expression.repository.UnavailableExpressionRepository;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the expression repository while preserving the no-DB scaffold context. */
@Configuration
public class ExpressionConfiguration {

  private static final Logger log = LoggerFactory.getLogger(ExpressionConfiguration.class);

  @Bean
  public ExpressionRepository expressionRepository(ObjectProvider<DataSource> dataSourceProvider) {
    DataSource dataSource = dataSourceProvider.getIfAvailable();
    if (dataSource == null) {
      log.info("No DataSource present; POST /expressions is unavailable (no-DB context)");
      return new UnavailableExpressionRepository();
    }
    return new JdbcExpressionRepository(new JdbcTemplate(dataSource));
  }
}
