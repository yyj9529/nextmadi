package com.phraselog.usage.config;

import com.phraselog.usage.repository.JdbcAnonymousTranscriptionUsageRepository;
import com.phraselog.usage.service.AnonymousTranscriptionUsageService;
import com.phraselog.usage.service.ClientIpResolver;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link AnonymousAnalysisUsageConfiguration} 과 같은 {@link ObjectProvider} 패턴으로 {@link
 * AnonymousTranscriptionUsageService} 를 배선한다. 그 클래스의 주석에 적힌 {@code @ConditionalOnBean} 순서 함정을 그대로
 * 피하기 위한 것이다.
 *
 * <p>DataSource 가 없는 no-DB 스캐폴드 컨텍스트에서는 null 빈을 등록한다. 그 컨텍스트에서는 전사 라우트가 호출되지 않는다.
 */
@Configuration
public class AnonymousTranscriptionUsageConfiguration {

  private static final Logger log =
      LoggerFactory.getLogger(AnonymousTranscriptionUsageConfiguration.class);

  @Bean
  AnonymousTranscriptionUsageService anonymousTranscriptionUsageService(
      ObjectProvider<DataSource> dataSourceProvider, ClientIpResolver clientIpResolver) {
    DataSource dataSource = dataSourceProvider.getIfAvailable();
    if (dataSource == null) {
      log.info(
          "No DataSource present; anonymous transcription rate limiting is unavailable (no-DB context)");
      return null;
    }
    return new AnonymousTranscriptionUsageService(
        new JdbcAnonymousTranscriptionUsageRepository(new JdbcTemplate(dataSource)),
        clientIpResolver);
  }
}
