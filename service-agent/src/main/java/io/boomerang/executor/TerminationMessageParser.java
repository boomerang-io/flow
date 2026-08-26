package io.boomerang.executor;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import io.boomerang.common.model.RunResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Parses a Kubernetes container termination message into declared Task Results. Tasks emit
 * either a JSON object ({@code {"name": "value"}}) or Tekton's array form
 * ({@code [{"key": .., "value": ..}]}). Empty or non-JSON input yields no Results.
 */
public abstract class TerminationMessageParser {

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
      JsonElement element = new Gson().fromJson(message, JsonElement.class);
      if (element == null) {
        return List.of();
      } else if (element.isJsonArray()) {
        element
            .getAsJsonArray()
            .forEach(
                item -> {
                  if (item.isJsonObject()) {
                    JsonObject obj = item.getAsJsonObject();
                    if (obj.has("key") && obj.has("value")) {
                      parsed.add(new RunResult(obj.get("key").getAsString(), obj.get("value").getAsString()));
                    }
                  }
                });
      } else if (element.isJsonObject()) {
        element
            .getAsJsonObject()
            .entrySet()
            .forEach(
                entry -> {
                  JsonElement value = entry.getValue();
                  parsed.add(
                      new RunResult(
                          entry.getKey(), value.isJsonPrimitive() ? value.getAsString() : value.toString()));
                });
      } else {
        return List.of();
      }
    } catch (JsonSyntaxException e) {
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
