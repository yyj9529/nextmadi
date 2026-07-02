package com.phraselog.storage;

/**
 * TTS 오디오 객체의 S3 저장/서명 URL 경계. (#30)
 *
 * <p>구현체는 prod에서 실제 S3에 붙는 {@link S3AudioStorage}, AWS가 없는 기본/테스트 컨텍스트에서는 {@link
 * UnavailableAudioStorage}로 갈린다 — 선택은 {@link AudioStorageConfiguration}이 {@code ObjectProvider}로
 * 수행.
 */
public interface AudioStorage {

  /** {@code key}에 오디오 바이트를 업로드한다(동일 키 재업로드는 멱등적 덮어쓰기). */
  void putAudio(String key, byte[] bytes, String contentType);

  /** {@code key} 객체에 대한 GET 서명 URL을 설정된 만료로 생성한다. */
  String presignGet(String key);
}
