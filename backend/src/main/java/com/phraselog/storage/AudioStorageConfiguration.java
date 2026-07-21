package com.phraselog.storage;

import io.awspring.cloud.s3.S3Template;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * {@link AudioStorage} 빈을 AWS S3 가용성에 따라 배선한다.
 *
 * <p>{@code PracticeConfiguration}/{@code AnalysisConfiguration}의 {@code
 * ObjectProvider<DataSource>} 게이트와 같은 방식: {@code spring.cloud.aws.s3.enabled=false}(기본/테스트)면 {@code
 * S3Template}/{@code S3Presigner} 빈이 없으므로 {@link UnavailableAudioStorage}로, prod에서 둘 다 있고 버킷이 설정돼
 * 있으면 {@link S3AudioStorage}로 배선한다.
 */
@Configuration
public class AudioStorageConfiguration {

  private static final Logger log = LoggerFactory.getLogger(AudioStorageConfiguration.class);

  // local 프로필은 LocalAudioStorageConfiguration이 파일시스템 기반 AudioStorage를 배선한다(#118).
  // 이 빈은 prod(S3)와 default/test(Unavailable 폴백)에서만 활성화해 빈 중복 정의를 막는다.
  @Bean
  @Profile("!local")
  public AudioStorage audioStorage(
      ObjectProvider<S3Template> s3TemplateProvider,
      ObjectProvider<S3Presigner> s3PresignerProvider,
      @Value("${phraselog.tts.s3.bucket:}") String bucket,
      @Value("${phraselog.tts.s3.presign-expiry:PT12H}") Duration presignExpiry) {
    S3Template s3Template = s3TemplateProvider.getIfAvailable();
    S3Presigner s3Presigner = s3PresignerProvider.getIfAvailable();
    if (s3Template == null || s3Presigner == null || !StringUtils.hasText(bucket)) {
      log.info(
          "S3 not configured (template={}, presigner={}, bucket={}); TTS audio storage unavailable",
          s3Template != null,
          s3Presigner != null,
          StringUtils.hasText(bucket));
      return new UnavailableAudioStorage();
    }
    return new S3AudioStorage(s3Template, s3Presigner, bucket, presignExpiry);
  }
}
