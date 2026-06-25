package com.phraselog.transcription.service;

import org.springframework.stereotype.Component;

@Component
public class WebmOpusInspector {

  private static final int EBML = 0x1A45DFA3;
  private static final int DOC_TYPE = 0x4282;
  private static final int SEGMENT = 0x18538067;
  private static final int INFO = 0x1549A966;
  private static final int TIMECODE_SCALE = 0x2AD7B1;
  private static final int DURATION = 0x4489;
  private static final int TRACKS = 0x1654AE6B;
  private static final int TRACK_ENTRY = 0xAE;
  private static final int CODEC_ID = 0x86;

  private static final double MAX_DURATION_SECONDS = 60.0;

  public AudioMetadata inspect(byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      throw new InvalidAudioException("audio is required.");
    }

    State state = new State();
    try {
      Parser parser = new Parser(bytes, state);
      parser.parseUntil(bytes.length);
    } catch (RuntimeException e) {
      if (e instanceof InvalidAudioException invalid) {
        throw invalid;
      }
      throw new InvalidAudioException("audio must be a valid WebM/Opus file.");
    }

    if (!state.sawEbml || !state.webmDocType || !state.sawSegment) {
      throw new InvalidAudioException("audio must be a valid WebM file.");
    }
    if (!state.hasOpusTrack) {
      throw new InvalidAudioException("audio must contain an Opus track.");
    }
    if (state.durationSeconds == null) {
      throw new InvalidAudioException("audio duration metadata is required.");
    }
    if (state.durationSeconds <= 0) {
      throw new InvalidAudioException("audio duration must be greater than 0 seconds.");
    }
    if (state.durationSeconds > MAX_DURATION_SECONDS) {
      throw new InvalidAudioException("audio must be at most 60 seconds.");
    }
    return new AudioMetadata(state.durationSeconds);
  }

  public record AudioMetadata(double durationSeconds) {}

  public static class InvalidAudioException extends RuntimeException {
    public InvalidAudioException(String message) {
      super(message);
    }
  }

  private static final class State {
    private boolean sawEbml;
    private boolean webmDocType;
    private boolean sawSegment;
    private boolean hasOpusTrack;
    private long timecodeScale = 1_000_000L;
    private Double durationSeconds;
  }

  private static final class Parser {
    private final byte[] bytes;
    private final State state;
    private int offset;

    private Parser(byte[] bytes, State state) {
      this.bytes = bytes;
      this.state = state;
    }

    private void parseUntil(int limit) {
      while (offset < limit) {
        int id = readId(limit);
        long size = readSize(limit);
        int bodyStart = offset;
        int bodyEnd = checkedEnd(bodyStart, size, limit);
        parseElement(id, bodyStart, bodyEnd);
        offset = bodyEnd;
      }
    }

    private void parseElement(int id, int bodyStart, int bodyEnd) {
      switch (id) {
        case EBML -> {
          state.sawEbml = true;
          parseChildren(bodyStart, bodyEnd);
        }
        case DOC_TYPE -> state.webmDocType = "webm".equals(readAscii(bodyStart, bodyEnd));
        case SEGMENT -> {
          state.sawSegment = true;
          parseChildren(bodyStart, bodyEnd);
        }
        case INFO, TRACKS, TRACK_ENTRY -> parseChildren(bodyStart, bodyEnd);
        case TIMECODE_SCALE -> state.timecodeScale = readUnsigned(bodyStart, bodyEnd);
        case DURATION ->
            state.durationSeconds =
                readFloat(bodyStart, bodyEnd) * state.timecodeScale / 1_000_000_000.0;
        case CODEC_ID -> {
          if ("A_OPUS".equals(readAscii(bodyStart, bodyEnd))) {
            state.hasOpusTrack = true;
          }
        }
        default -> {
          // Non-structural media payloads are intentionally skipped.
        }
      }
    }

    private void parseChildren(int bodyStart, int bodyEnd) {
      int original = offset;
      offset = bodyStart;
      parseUntil(bodyEnd);
      offset = original;
    }

    private int readId(int limit) {
      requireRemaining(1, limit);
      int first = bytes[offset] & 0xFF;
      int length = vintLength(first);
      requireRemaining(length, limit);
      int value = 0;
      for (int i = 0; i < length; i++) {
        value = (value << 8) | (bytes[offset++] & 0xFF);
      }
      return value;
    }

    private long readSize(int limit) {
      requireRemaining(1, limit);
      int first = bytes[offset++] & 0xFF;
      int length = vintLength(first);
      requireRemaining(length - 1, limit);
      long value = first & (0xFF >>> length);
      for (int i = 1; i < length; i++) {
        value = (value << 8) | (bytes[offset++] & 0xFF);
      }
      return value;
    }

    private int vintLength(int firstByte) {
      for (int length = 1; length <= 8; length++) {
        if ((firstByte & (0x80 >>> (length - 1))) != 0) {
          return length;
        }
      }
      throw new InvalidAudioException("audio must be a valid WebM file.");
    }

    private int checkedEnd(int start, long size, int limit) {
      if (size < 0 || size > Integer.MAX_VALUE || start + size > limit) {
        throw new InvalidAudioException("audio must be a valid WebM file.");
      }
      return (int) (start + size);
    }

    private void requireRemaining(int length, int limit) {
      if (offset + length > limit) {
        throw new InvalidAudioException("audio must be a valid WebM file.");
      }
    }

    private String readAscii(int start, int end) {
      return new String(bytes, start, end - start, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private long readUnsigned(int start, int end) {
      long value = 0;
      for (int i = start; i < end; i++) {
        value = (value << 8) | (bytes[i] & 0xFFL);
      }
      return value;
    }

    private double readFloat(int start, int end) {
      int length = end - start;
      if (length == 4) {
        int bits = 0;
        for (int i = start; i < end; i++) {
          bits = (bits << 8) | (bytes[i] & 0xFF);
        }
        return Float.intBitsToFloat(bits);
      }
      if (length == 8) {
        long bits = 0;
        for (int i = start; i < end; i++) {
          bits = (bits << 8) | (bytes[i] & 0xFFL);
        }
        return Double.longBitsToDouble(bits);
      }
      throw new InvalidAudioException("audio duration metadata is invalid.");
    }
  }
}
