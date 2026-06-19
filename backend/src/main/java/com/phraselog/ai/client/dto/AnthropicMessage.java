package com.phraselog.ai.client.dto;

/**
 * One message in a request to the Anthropic API. Corresponds to {@code POST /messages}, where a
 * message has a role ("user" or "assistant") and text content.
 */
public record AnthropicMessage(String role, String content) {

  public AnthropicMessage {
    if (role == null || role.isBlank()) {
      throw new IllegalArgumentException("role is required");
    }
    if (content == null) {
      content = "";
    }
  }

  /** Convenience constructor for user message. */
  public static AnthropicMessage user(String content) {
    return new AnthropicMessage("user", content);
  }

  /** Convenience constructor for assistant message. */
  public static AnthropicMessage assistant(String content) {
    return new AnthropicMessage("assistant", content);
  }
}
