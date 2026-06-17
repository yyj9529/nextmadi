package com.phraselog.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import org.everit.json.schema.Schema;
import org.everit.json.schema.SchemaException;
import org.everit.json.schema.loader.SchemaLoader;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Validates JSON responses against versioned JSON schema files bundled in the classpath under
 * {@code schemas/}.
 */
@Component
public class JsonSchemaValidator {

  private static final Logger log = LoggerFactory.getLogger(JsonSchemaValidator.class);

  private final ObjectMapper objectMapper;
  private final Cache schemaCache = new Cache();

  public JsonSchemaValidator(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /**
   * Validates a JSON node against the named schema.
   *
   * @param schemaId the schema identifier, e.g. "s07_analysis_v1"
   * @param jsonNode the JSON to validate
   * @throws JsonSchemaValidationException if validation fails
   */
  public void validate(String schemaId, JsonNode jsonNode) throws JsonSchemaValidationException {
    try {
      Schema schema = schemaCache.getOrLoad(schemaId, this::loadSchema);
      schema.validate(new JSONObject(jsonNode.toString()));
    } catch (SchemaException e) {
      throw new JsonSchemaValidationException(
          "Schema validation failed for " + schemaId + ": " + e.getMessage(), e);
    } catch (Exception e) {
      throw new JsonSchemaValidationException("Error validating against schema " + schemaId, e);
    }
  }

  private Schema loadSchema(String schemaId) throws IOException {
    String resourcePath = "schemas/" + schemaId + ".json";
    ClassPathResource resource = new ClassPathResource(resourcePath);

    if (!resource.exists()) {
      throw new IOException("Schema file not found: " + resourcePath);
    }

    try (InputStream in = resource.getInputStream()) {
      JSONObject schemaJson = new JSONObject(new String(in.readAllBytes()));
      return SchemaLoader.load(schemaJson);
    }
  }

  /** Exception thrown when JSON schema validation fails. */
  public static class JsonSchemaValidationException extends Exception {

    public JsonSchemaValidationException(String message) {
      super(message);
    }

    public JsonSchemaValidationException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** Simple thread-safe cache for loaded schemas. */
  private static class Cache {

    private final java.util.Map<String, Schema> cache =
        new java.util.concurrent.ConcurrentHashMap<>();

    Schema getOrLoad(String key, CacheLoader<Schema> loader) throws Exception {
      return cache.computeIfAbsent(
          key,
          k -> {
            try {
              return loader.load(k);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
    }
  }

  @FunctionalInterface
  private interface CacheLoader<T> {

    T load(String schemaId) throws Exception;
  }
}
