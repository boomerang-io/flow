package io.boomerang.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.boomerang.dispatcher.sdk.model.RunResult;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the values budget handed to a task as RESULTS_MAX_BYTES: the limit less the envelope its
 * declared results take, checked against the payload each shape actually writes with empty values.
 */
public class ResultsBudgetTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final List<RunResult> ONE = declared("output");

  private static final List<RunResult> FIVE =
      declared("output", "usage", "model", "turns", "toolCalls");

  private static List<RunResult> declared(String... names) {
    return java.util.Arrays.stream(names).map(n -> new RunResult(n, null)).toList();
  }

  /** The Jobs executor's payload, compact JSON as Jackson writes it, every value empty. */
  private static int objectEnvelope(List<RunResult> results) {
    Map<String, String> payload = new LinkedHashMap<>();
    results.forEach(r -> payload.put(r.getName(), ""));
    return MAPPER.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8).length;
  }

  /** Tekton's termination message, start-time entry first, every value empty. */
  private static int tektonEnvelope(List<RunResult> results) {
    String startedAt = "{\"key\":\"StartedAt\",\"value\":\"2026-11-25T10:00:00.000+10:00\",\"type\":3}";
    String entries =
        results.stream()
            .map(r -> "{\"key\":\"" + r.getName() + "\",\"value\":\"\",\"type\":1}")
            .collect(Collectors.joining(","));
    String message = "[" + startedAt + (entries.isEmpty() ? "" : "," + entries) + "]";
    return message.getBytes(StandardCharsets.UTF_8).length;
  }

  @Test
  public void anObjectWithNoDeclaredResultsLosesOnlyItsBraces() {
    assertEquals(4094, ResultsBudget.forObject(4096, List.of()));
    assertEquals(4094, ResultsBudget.forObject(4096, null));
    assertEquals(4096 - objectEnvelope(List.of()), ResultsBudget.forObject(4096, List.of()));
  }

  @Test
  public void anObjectWithOneDeclaredResultLosesItsEntry() {
    // {"output":""} is 13 bytes.
    assertEquals(4083, ResultsBudget.forObject(4096, ONE));
    assertEquals(4096 - objectEnvelope(ONE), ResultsBudget.forObject(4096, ONE));
  }

  @Test
  public void anObjectWithFiveDeclaredResultsLosesEveryEntryAndTheCommasBetween() {
    // {"output":"","usage":"","model":"","turns":"","toolCalls":""} is 61 bytes.
    assertEquals(4035, ResultsBudget.forObject(4096, FIVE));
    assertEquals(4096 - objectEnvelope(FIVE), ResultsBudget.forObject(4096, FIVE));
  }

  @Test
  public void tektonsArrayWithNoDeclaredResultsStillCarriesItsStartTime() {
    assertEquals(4026, ResultsBudget.forTektonArray(4096, List.of()));
    assertEquals(4096 - tektonEnvelope(List.of()), ResultsBudget.forTektonArray(4096, List.of()));
  }

  @Test
  public void tektonsArrayWithOneDeclaredResultLosesItsEntry() {
    assertEquals(3989, ResultsBudget.forTektonArray(4096, ONE));
    assertEquals(4096 - tektonEnvelope(ONE), ResultsBudget.forTektonArray(4096, ONE));
  }

  @Test
  public void tektonsArrayWithFiveDeclaredResultsLosesEveryEntryAndTheCommasBetween() {
    assertEquals(3841, ResultsBudget.forTektonArray(4096, FIVE));
    assertEquals(4096 - tektonEnvelope(FIVE), ResultsBudget.forTektonArray(4096, FIVE));
  }

  @Test
  public void aRaisedTektonLimitKeepsTheSameEnvelope() {
    assertEquals(
        8192 - 4096 + ResultsBudget.forTektonArray(4096, FIVE),
        ResultsBudget.forTektonArray(8192, FIVE));
  }

  @Test
  public void aNameDeclaredTwiceIsWrittenOnceAndAnEscapedNameCountsItsEscapes() {
    assertEquals(
        ResultsBudget.forObject(4096, ONE), ResultsBudget.forObject(4096, declared("output", "output")));
    List<RunResult> quoted = declared("say\"hi");
    assertEquals(4096 - objectEnvelope(quoted), ResultsBudget.forObject(4096, quoted));
  }

  @Test
  public void aLimitSmallerThanTheEnvelopeLeavesNoBudget() {
    assertEquals(0, ResultsBudget.forObject(10, FIVE));
    assertEquals(0, ResultsBudget.forTektonArray(10, FIVE));
  }
}
