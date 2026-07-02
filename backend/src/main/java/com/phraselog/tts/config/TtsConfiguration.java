package com.phraselog.tts.config;

import com.phraselog.tts.repository.JdbcTtsAudioCacheRepository;
import com.phraselog.tts.repository.TtsAudioCacheRepository;
import com.phraselog.tts.repository.UnavailableTtsAudioCacheRepository;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code tts_audio_cache} 리포지토리를 {@code DataSource} 가용성에 따라 배선한다.
 *
 * <p>{@code PracticeConfiguration}과 동일한 {@code ObjectProvider<DataSource>} 게이트 — DB가 없는 scaffold
 * 컨텍스트는 {@link UnavailableTtsAudioCacheRepository}로 로딩되고, 실제 호출 시 503으로 실패한다. {@code
 * TtsPlaybackService} 자체는 일반 컴포넌트 스캔 빈이고, DB가 필요한 리포지토리만 여기서 게이트한다.
 */
@Configuration
public class TtsConfiguration {

  private static final Logger log = LoggerFactory.getLogger(TtsConfiguration.class);

  @Bean
  public TtsAudioCacheRepository ttsAudioCacheRepository(
      ObjectProvider<DataSource> dataSourceProvider) {
    DataSource dataSource = dataSourceProvider.getIfAvailable();
    if (dataSource == null) {
      log.info("No DataSource present; TTS audio cache is unavailable (no-DB context)");
      return new UnavailableTtsAudioCacheRepository();
    }
    return new JdbcTtsAudioCacheRepository(
        new JdbcTemplate(dataSource),
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
  }
}
