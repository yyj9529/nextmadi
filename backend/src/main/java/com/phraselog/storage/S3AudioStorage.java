package com.phraselog.storage;

import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Template;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * Spring Cloud AWS {@link S3Template}(업로드) + AWS SDK {@link S3Presigner}(서명 GET) 기반 {@link
 * AudioStorage}.
 *
 * <p>{@code @Component}가 아니라 {@link AudioStorageConfiguration}에서 직접 생성된다 — AWS 미구성 컨텍스트에서 {@code
 * S3Template}/{@code S3Presigner} 빈이 없을 때 이 클래스를 인스턴스화하지 않기 위해서다.
 */
public class S3AudioStorage implements AudioStorage {

  private final S3Template s3Template;
  private final S3Presigner s3Presigner;
  private final String bucket;
  private final Duration presignExpiry;

  public S3AudioStorage(
      S3Template s3Template, S3Presigner s3Presigner, String bucket, Duration presignExpiry) {
    this.s3Template = s3Template;
    this.s3Presigner = s3Presigner;
    this.bucket = bucket;
    this.presignExpiry = presignExpiry;
  }

  @Override
  public void putAudio(String key, byte[] bytes, String contentType) {
    s3Template.upload(
        bucket,
        key,
        new ByteArrayInputStream(bytes),
        ObjectMetadata.builder().contentType(contentType).build());
  }

  @Override
  public String presignGet(String key) {
    GetObjectPresignRequest presignRequest =
        GetObjectPresignRequest.builder()
            .signatureDuration(presignExpiry)
            .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
            .build();
    return s3Presigner.presignGetObject(presignRequest).url().toString();
  }
}
