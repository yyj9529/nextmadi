package com.phraselog.ai.logging;

import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Wires the {@link AiRequestLogStore} according to whether a {@code DataSource} is available. */
@Configuration
public class AiLoggingConfiguration {

  private static final Logger log = LoggerFactory.getLogger(AiLoggingConfiguration.class);

  /**
   * Picks the JDBC store when a {@code DataSource} bean exists, otherwise a no-op store.
   *
   * <p>{@link ObjectProvider#getIfAvailable()} resolves lazily at instantiation time, after all
   * bean definitions (including the autoconfigured {@code DataSource}) are registered. This avoids
   * the ordering pitfall of {@code @ConditionalOnBean} on a user-defined component, and lets the
   * no-DB scaffold context (which excludes {@code DataSourceAutoConfiguration}) still load.
   */
  @Bean
  public AiRequestLogStore aiRequestLogStore(ObjectProvider<DataSource> dataSourceProvider) {
    DataSource dataSource = dataSourceProvider.getIfAvailable();
    if (dataSource == null) {
      log.info("No DataSource present; ai_request_logs writes are disabled (NoOp store)");
      return new NoOpAiRequestLogStore();
    }
    return new JdbcAiRequestLogStore(new JdbcTemplate(dataSource));
  }
}
