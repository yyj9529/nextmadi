package com.phraselog.usage.config;

import com.phraselog.usage.repository.JdbcAnonymousAnalysisUsageRepository;
import com.phraselog.usage.service.AnonymousAnalysisUsageService;
import com.phraselog.usage.service.ClientIpResolver;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the {@link AnonymousAnalysisUsageService} according to whether a {@code DataSource} is
 * available, using the same {@link ObjectProvider} pattern as {@link
 * com.phraselog.ai.logging.config.AiLoggingConfiguration} and {@link
 * com.phraselog.analysis.config.AnalysisConfiguration}.
 *
 * <p>{@link ObjectProvider#getIfAvailable()} resolves lazily at instantiation time, after all bean
 * definitions (including the autoconfigured {@code DataSource}/{@code JdbcTemplate}) are
 * registered. This avoids the ordering pitfall of {@code @ConditionalOnBean} on a user-defined
 * component — that condition is evaluated before the autoconfigured {@code JdbcTemplate} bean is
 * registered, so it never matched and the service bean was silently absent even when a DataSource
 * was present. {@link com.phraselog.analysis.service.AnalysisService} then failed at request time
 * on {@code usageServiceProvider.getObject()}.
 *
 * <p>When no {@code DataSource} is present (the no-DB scaffold context) the bean method returns
 * {@code null}, so the container registers a null bean and {@code getObject()} throws as before —
 * the analysis routes are never exercised in that context.
 */
@Configuration
public class AnonymousAnalysisUsageConfiguration {

  private static final Logger log =
      LoggerFactory.getLogger(AnonymousAnalysisUsageConfiguration.class);

  @Bean
  AnonymousAnalysisUsageService anonymousAnalysisUsageService(
      ObjectProvider<DataSource> dataSourceProvider, ClientIpResolver clientIpResolver) {
    DataSource dataSource = dataSourceProvider.getIfAvailable();
    if (dataSource == null) {
      log.info(
          "No DataSource present; anonymous analysis rate limiting is unavailable (no-DB context)");
      return null;
    }
    return new AnonymousAnalysisUsageService(
        new JdbcAnonymousAnalysisUsageRepository(new JdbcTemplate(dataSource)), clientIpResolver);
  }
}
