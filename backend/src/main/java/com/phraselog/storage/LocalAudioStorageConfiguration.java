package com.phraselog.storage;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * {@code local} 프로필 전용 오디오 저장 배선. (#118)
 *
 * <p>S3가 꺼진 로컬에서 {@link AudioStorageConfiguration}의 Unavailable 폴백 대신 {@link
 * LocalFilesystemAudioStorage}를 쓴다. 같은 {@code baseDir}를 (1) 저장 빈과 (2) {@code /local-audio/**} 정적 서빙
 * 핸들러가 공유하므로, 저장한 MP3를 브라우저가 곧바로 GET 해 재생할 수 있다.
 *
 * <p>기본값이 내장돼 있어 gitignore된 {@code application-local.yml}에 별도 설정을 넣지 않아도 동작한다. 저장 위치만 바꾸고 싶으면
 * {@code PHRASELOG_LOCAL_AUDIO_DIR}/{@code PHRASELOG_LOCAL_AUDIO_BASE_URL}로 덮어쓴다.
 *
 * <p>{@code /local-audio/**}는 {@code /api/v1} 접두사가 아니라 {@code InternalAuthFilter}의 관심사 밖이다 — 브라우저가
 * 인증 헤더 없이 직접 오디오를 받는 로컬 전용 공개 경로다.
 */
@Configuration
@Profile("local")
public class LocalAudioStorageConfiguration implements WebMvcConfigurer {

  private final Path baseDir;
  private final String publicBaseUrl;

  public LocalAudioStorageConfiguration(
      @Value("${phraselog.tts.local-storage.dir:${java.io.tmpdir}/phraselog-audio}") String dir,
      @Value("${phraselog.tts.local-storage.public-base-url:http://localhost:8080/local-audio}")
          String publicBaseUrl) {
    this.baseDir = Path.of(dir).toAbsolutePath().normalize();
    this.publicBaseUrl = publicBaseUrl;
  }

  @Bean
  public AudioStorage audioStorage() {
    return new LocalFilesystemAudioStorage(baseDir, publicBaseUrl);
  }

  @Override
  public void addResourceHandlers(ResourceHandlerRegistry registry) {
    // baseDir.toUri()는 "file:///.../phraselog-audio/"로 끝나 정적 위치 규약(끝에 '/')을 만족한다.
    // PathResourceResolver가 경로 탈출을 차단하고, MediaTypeFactory가 .mp3 → audio/mpeg를 붙인다.
    registry.addResourceHandler("/local-audio/**").addResourceLocations(baseDir.toUri().toString());
  }
}
