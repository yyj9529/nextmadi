package com.phraselog.transcription;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

public final class WebmTestFixtures {

  private WebmTestFixtures() {}

  public static byte[] webmOpus(double durationSeconds) {
    return webm(durationSeconds, "A_OPUS");
  }

  public static byte[] webmWithCodec(double durationSeconds, String codecId) {
    return webm(durationSeconds, codecId);
  }

  public static byte[] webmWithoutDuration() {
    return ebmlDocument(
        element(0x1A45DFA3, element(0x4282, ascii("webm"))),
        element(
            0x18538067, element(0x1549A966, uintElement(0x2AD7B1, 1_000_000)), tracks("A_OPUS")));
  }

  private static byte[] webm(double durationSeconds, String codecId) {
    byte[] info =
        element(
            0x1549A966,
            uintElement(0x2AD7B1, 1_000_000),
            doubleElement(0x4489, durationSeconds * 1000.0));
    return ebmlDocument(
        element(0x1A45DFA3, element(0x4282, ascii("webm"))),
        element(0x18538067, info, tracks(codecId)));
  }

  private static byte[] tracks(String codecId) {
    return element(0x1654AE6B, element(0xAE, element(0x86, ascii(codecId))));
  }

  private static byte[] ebmlDocument(byte[]... children) {
    return concat(children);
  }

  private static byte[] element(int id, byte[]... children) {
    byte[] body = concat(children);
    return concat(idBytes(id), sizeBytes(body.length), body);
  }

  private static byte[] uintElement(int id, long value) {
    return element(id, unsigned(value));
  }

  private static byte[] doubleElement(int id, double value) {
    long bits = Double.doubleToLongBits(value);
    byte[] bytes = new byte[8];
    for (int i = 7; i >= 0; i--) {
      bytes[i] = (byte) (bits & 0xFF);
      bits >>>= 8;
    }
    return element(id, bytes);
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static byte[] unsigned(long value) {
    if (value == 0) {
      return new byte[] {0};
    }
    int length = 0;
    long copy = value;
    while (copy > 0) {
      length++;
      copy >>>= 8;
    }
    byte[] bytes = new byte[length];
    for (int i = length - 1; i >= 0; i--) {
      bytes[i] = (byte) (value & 0xFF);
      value >>>= 8;
    }
    return bytes;
  }

  private static byte[] idBytes(int id) {
    int length;
    if ((id & 0xFF000000) != 0) {
      length = 4;
    } else if ((id & 0x00FF0000) != 0) {
      length = 3;
    } else if ((id & 0x0000FF00) != 0) {
      length = 2;
    } else {
      length = 1;
    }
    byte[] bytes = new byte[length];
    for (int i = length - 1; i >= 0; i--) {
      bytes[i] = (byte) (id & 0xFF);
      id >>>= 8;
    }
    return bytes;
  }

  private static byte[] sizeBytes(int size) {
    if (size < 0x7F) {
      return new byte[] {(byte) (0x80 | size)};
    }
    if (size < 0x3FFF) {
      return new byte[] {(byte) (0x40 | (size >> 8)), (byte) (size & 0xFF)};
    }
    throw new IllegalArgumentException("fixture element too large");
  }

  private static byte[] concat(byte[]... chunks) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] chunk : chunks) {
      out.writeBytes(chunk);
    }
    return out.toByteArray();
  }
}
