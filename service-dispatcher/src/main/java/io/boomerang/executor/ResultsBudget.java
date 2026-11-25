package io.boomerang.executor;

import io.boomerang.dispatcher.sdk.model.RunResult;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The bytes a task may spend on its result values, handed to it as {@code RESULTS_MAX_BYTES}. The
 * limit Kubernetes enforces is on the whole termination message, envelope included, so a task told
 * the raw limit overflows it: a task that budgeted 3,403 bytes of values wrote 4,098. The envelope
 * depends only on the declared result names and is subtracted here; the JSON escaping of each value
 * depends on the value and is the task's to count (an escaped character takes two bytes, or six).
 *
 * <p>Both shapes assume compact JSON, no whitespace between tokens, and every declared result
 * written once. A task that writes fewer results has that much more room.
 */
public final class ResultsBudget {

  /** {@code {"name":"value"}}: the quotes around a value, the colon and the comma. */
  private static final int OBJECT_PER_RESULT = 4;

  /** {@code {}} around the object. */
  private static final int OBJECT_WRAPPER = 2;

  /**
   * Tekton's entry for one result, {@code {"key":"name","value":"…","type":1}}, less the name:
   * {@code {"key":} (7), {@code ,"value":} (9), the value's quotes (2), {@code ,"type":1}} (10).
   */
  private static final int TEKTON_PER_RESULT = 28;

  /** {@code []} around Tekton's array. */
  private static final int TEKTON_WRAPPER = 2;

  /**
   * The step's start time, which Tekton's entrypoint writes into every termination message beside
   * the results: {@code {"key":"StartedAt","value":"2006-01-02T15:04:05.000+07:00","type":3}}, 68
   * bytes with a zone offset (63 in UTC), plus the comma that separates it from a result.
   */
  private static final int TEKTON_STARTED_AT = 69;

  private ResultsBudget() {}

  /**
   * The values budget for a task that writes one JSON object, {@code {"name":"value",…}}, as the
   * Jobs executor's termination message: the limit less the braces, and per declared result its
   * quoted name, the colon, the value's quotes and a comma (one fewer comma than results).
   */
  public static int forObject(int maxBytes, List<RunResult> declaredResults) {
    Set<String> names = names(declaredResults);
    int envelope = OBJECT_WRAPPER;
    for (String name : names) {
      envelope += quotedLength(name) + OBJECT_PER_RESULT;
    }
    if (!names.isEmpty()) {
      envelope -= 1; // the last result carries no comma
    }
    return Math.max(0, maxBytes - envelope);
  }

  /**
   * The values budget for a Tekton task, whose entrypoint wraps one file per result into
   * {@code [{"key":"name","value":"…","type":1},…]} beside its own start-time entry: the limit less
   * the brackets, the start-time entry, and per declared result its entry and a comma.
   */
  public static int forTektonArray(int maxBytes, List<RunResult> declaredResults) {
    Set<String> names = names(declaredResults);
    int envelope = TEKTON_WRAPPER + TEKTON_STARTED_AT - 1; // no comma when it is the only entry
    for (String name : names) {
      envelope += quotedLength(name) + TEKTON_PER_RESULT + 1;
    }
    return Math.max(0, maxBytes - envelope);
  }

  /** Distinct declared names: a JSON object, and Tekton's results directory, hold each once. */
  private static Set<String> names(List<RunResult> declaredResults) {
    Set<String> names = new LinkedHashSet<>();
    Optional.ofNullable(declaredResults).orElse(List.of()).stream()
        .filter(Objects::nonNull)
        .map(RunResult::getName)
        .filter(Objects::nonNull)
        .forEach(names::add);
    return names;
  }

  /** A name as JSON writes it: quoted, with a quote or backslash escaped, counted in UTF-8 bytes. */
  private static int quotedLength(String name) {
    int length = 2 + name.getBytes(StandardCharsets.UTF_8).length;
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (c == '"' || c == '\\') {
        length += 1; // \" and \\
      } else if (c < 0x20) {
        length += 5; // a six-byte unicode escape
      }
    }
    return length;
  }
}
