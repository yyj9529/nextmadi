package com.phraselog.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 개발용 로컬 파일시스템 기반 {@link AudioStorage}. (#118)
 *
 * <p>노트북에서 S3 없이 TTS 오디오를 실제로 저장/서빙하기 위한 구현. {@code putAudio}는 바이트를 {@code baseDir/key}에 쓰고, {@code
 * presignGet}은 로컬 정적 서빙 엔드포인트({@code publicBaseUrl}) 아래의 브라우저가 직접 접근 가능한 URL을 돌려준다. 서명·만료 개념은 없다 —
 * 로컬 전용이라 서명이 필요 없고, 프론트의 {@code new Audio(url)} 재생만 만족하면 된다.
 *
 * <p>{@code @Component}가 아니라 {@link LocalAudioStorageConfiguration}에서 {@code local} 프로필일 때만 생성된다.
 * prod/test는 {@link AudioStorageConfiguration}이 S3/Unavailable로 배선한다.
 */
public final class LocalFilesystemAudioStorage implements AudioStorage {

  private static final Logger log = LoggerFactory.getLogger(LocalFilesystemAudioStorage.class);

  private final Path baseDir;
  private final String publicBaseUrl;

  public LocalFilesystemAudioStorage(Path baseDir, String publicBaseUrl) {
    this.baseDir = baseDir.toAbsolutePath().normalize();
    // presignGet에서 key 앞에 "/"를 붙이므로, 뒤쪽 슬래시는 제거해 이중 슬래시를 막는다.
    this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
    try {
      Files.createDirectories(this.baseDir);
    } catch (IOException e) {
      throw new UncheckedIOException(
          "Failed to create local audio storage dir: " + this.baseDir, e);
    }
    log.info(
        "Local audio storage active: dir={}, publicBaseUrl={}", this.baseDir, this.publicBaseUrl);
  }

  @Override
  public void putAudio(String key, byte[] bytes, String contentType) {
    Path target = resolveSafely(key);
    try {
      Files.createDirectories(target.getParent());
      Files.write(target, bytes);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to write local audio: " + key, e);
    }
  }

  @Override
  public String presignGet(String key) {
    // key는 "tts/{voice}/{hash}.mp3" 형태의 안전한 상대 경로다. 정적 핸들러가 같은 baseDir에서 서빙한다.
    return publicBaseUrl + "/" + key;
  }

  /**
   * {@code key}를 {@code baseDir} 아래로만 해석되도록 강제한다. voice_id는 사용자 입력에서 올 수 있으므로 {@code ../} 같은 경로 탈출을
   * 차단한다(정규화 후 baseDir 하위인지 검증).
   */
  private Path resolveSafely(String key) {
    Path resolved = baseDir.resolve(key).normalize();
    if (!resolved.startsWith(baseDir)) {
      throw new IllegalArgumentException("Audio key escapes base dir: " + key);
    }
    return resolved;
  }
}
