package com.phraselog.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFilesystemAudioStorageTests {

  private static final byte[] MP3 = {(byte) 0xFF, (byte) 0xFB, 0x10, 0x00};
  private static final String KEY = "tts/shimmer/abc123.mp3";

  @Test
  void putAudioWritesBytesUnderBaseDir(@TempDir Path baseDir) throws Exception {
    LocalFilesystemAudioStorage storage =
        new LocalFilesystemAudioStorage(baseDir, "http://localhost:8080/local-audio");

    storage.putAudio(KEY, MP3, "audio/mpeg");

    Path written = baseDir.resolve(KEY);
    assertThat(Files.exists(written)).isTrue();
    assertThat(Files.readAllBytes(written)).isEqualTo(MP3);
  }

  @Test
  void putAudioOverwritesSameKeyIdempotently(@TempDir Path baseDir) throws Exception {
    LocalFilesystemAudioStorage storage =
        new LocalFilesystemAudioStorage(baseDir, "http://localhost:8080/local-audio");

    storage.putAudio(KEY, MP3, "audio/mpeg");
    byte[] second = {0x00, 0x11, 0x22};
    storage.putAudio(KEY, second, "audio/mpeg");

    assertThat(Files.readAllBytes(baseDir.resolve(KEY))).isEqualTo(second);
  }

  @Test
  void presignGetReturnsBrowserFetchableUrlWithoutDoubleSlash(@TempDir Path baseDir) {
    LocalFilesystemAudioStorage storage =
        new LocalFilesystemAudioStorage(baseDir, "http://localhost:8080/local-audio/");

    assertThat(storage.presignGet(KEY))
        .isEqualTo("http://localhost:8080/local-audio/tts/shimmer/abc123.mp3");
  }

  @Test
  void putAudioRejectsPathTraversalKey(@TempDir Path baseDir) {
    LocalFilesystemAudioStorage storage =
        new LocalFilesystemAudioStorage(baseDir, "http://localhost:8080/local-audio");

    assertThatThrownBy(() -> storage.putAudio("tts/../../evil.mp3", MP3, "audio/mpeg"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
