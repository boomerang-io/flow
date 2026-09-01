package io.boomerang.executor;

import io.boomerang.common.model.RunResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Parses a Kubernetes container termination message into declared Task Results. Tasks emit
 * either a JSON object ({@code {"name": "value"}}) or Tekton's array form
 * ({@code [{"key": .., "value": ..}]}). Empty or non-JSON input yields no Results.
 */
public abstract class TerminationMessageParser {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private TerminationMessageParser() {}

  /**
   * Parse the termination message. When {@code declaredResults} is non-empty, only the names it
   * lists are returned; otherwise everything parsed is returned.
   */
  public static List<RunResult> parse(String message, List<RunResult> declaredResults) {
    if (message == null || message.isBlank()) {
      return List.of();
    }

    List<RunResult> parsed = new ArrayList<>();
    try {
      JsonNode node = OBJECT_MAPPER.readTree(message);
      if (node.isArray()) {
        node.forEach(
            item -> {
              if (item.isObject() && item.has("key") && item.has("value")) {
                parsed.add(new RunResult(item.get("key").asText(), item.get("value").asText()));
              }
            });
      } else if (node.isObject()) {
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
          JsonNode value = entry.getValue();
          parsed.add(
              new RunResult(
                  entry.getKey(), value.isValueNode() ? value.asText() : value.toString()));
        }
      } else {
        return List.of();
      }
    } catch (JacksonException e) {
      return List.of();
    }

    Set<String> declaredNames =
        Optional.ofNullable(declaredResults).orElse(List.of()).stream()
            .map(RunResult::getName)
            .collect(Collectors.toSet());
    return declaredNames.isEmpty()
        ? parsed
        : parsed.stream().filter(r -> declaredNames.contains(r.getName())).collect(Collectors.toList());
  }
}
