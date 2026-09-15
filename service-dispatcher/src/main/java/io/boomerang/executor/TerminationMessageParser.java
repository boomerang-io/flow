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
 * ({@code [{"key": .., "value": ..}]}).
 *
 * <p>"No results" and "this is not a results payload" are different answers and the caller has to
 * tell them apart: Kubernetes truncates a termination message above 4096 bytes, and the truncated
 * prefix is broken JSON. Reading that as "no results" is how an oversize payload used to end as a
 * success with every result missing and nothing said. An absent Optional is the unparseable case.
 */
public abstract class TerminationMessageParser {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private TerminationMessageParser() {}

  /**
   * Parse the termination message. When {@code declaredResults} is non-empty, only the names it
   * lists are returned; otherwise everything parsed is returned. An empty or absent message is an
   * empty result list; a message that is not a JSON object or array is {@code Optional.empty()}.
   */
  public static Optional<List<RunResult>> parse(String message, List<RunResult> declaredResults) {
    if (message == null || message.isBlank()) {
      return Optional.of(List.of());
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
        return Optional.empty();
      }
    } catch (JacksonException e) {
      return Optional.empty();
    }

    Set<String> declaredNames =
        Optional.ofNullable(declaredResults).orElse(List.of()).stream()
            .map(RunResult::getName)
            .collect(Collectors.toSet());
    return Optional.of(
        declaredNames.isEmpty()
            ? parsed
            : parsed.stream()
                .filter(r -> declaredNames.contains(r.getName()))
                .collect(Collectors.toList()));
  }
}
