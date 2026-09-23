package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.ParamType;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.TaskEnvVar;
import io.boomerang.engine.repository.TaskRunRepository;
import io.boomerang.engine.repository.WorkflowRunRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Characterization tests that PIN the current behaviour of ParameterManager.resolveParam across
 * its five reference shapes plus the not-found and plain-string passthrough cases. These exist so
 * the decomposition of resolveParam into named handlers is provably behaviour-preserving - they
 * must stay green before and after that refactor. Pure unit test: the repositories are mocked and
 * canned TaskRun results drive the task-result shape.
 */
class ParameterManagerTest {

  private static final String WF = "wf1";

  private TaskRunRepository taskRunRepository;
  private ParameterManager parameterManager;

  @BeforeEach
  void setUp() {
    taskRunRepository = mock(TaskRunRepository.class);
    parameterManager =
        new ParameterManager(
            mock(WorkflowRunRepository.class), taskRunRepository, new ObjectMapper());
  }

  // (a) plain param: $(params.<name>) resolves from the flattened layer.
  @Test
  void resolvesPlainParam() {
    WorkflowRunEntity run = run(str("src", "hello"), str("ref", "$(params.src)"));
    parameterManager.resolveParamLayers(run, Optional.empty());
    assertEquals("hello", resolved(run, "ref"));
  }

  // (b) object-path param: $(params.<name>.<jsonpath>) reads into an object param value.
  @Test
  void resolvesObjectPathParam() {
    WorkflowRunEntity run =
        run(object("obj", Map.of("k", "vk")), str("ref", "$(params.obj.k)"));
    parameterManager.resolveParamLayers(run, Optional.empty());
    assertEquals("vk", resolved(run, "ref"));
  }

  // (c) scoped param: $(<scope>.params.<name>) resolves from that layer (global here).
  @Test
  void resolvesScopedParam() {
    WorkflowRunEntity run = run(str("ref", "$(global.params.g1)"));
    run.getAnnotations().put("boomerang.io/global-params", Map.of("g1", "gv"));
    parameterManager.resolveParamLayers(run, Optional.empty());
    assertEquals("gv", resolved(run, "ref"));
  }

  // (d) scoped object-path: $(<scope>.params.<name>.<jsonpath>).
  @Test
  void resolvesScopedObjectPathParam() {
    WorkflowRunEntity run = run(str("ref", "$(context.params.co.k)"));
    // The context-params layer must be mutable: the engine puts workflowrun-* context keys into
    // it. In production it is a deserialized-JSON HashMap, so mirror that here.
    run.getAnnotations()
        .put("boomerang.io/context-params", new HashMap<>(Map.of("co", Map.of("k", "cv"))));
    parameterManager.resolveParamLayers(run, Optional.empty());
    assertEquals("cv", resolved(run, "ref"));
  }

  // (e) task-result reference: $(tasks.<name>.results.<result>).
  @Test
  void resolvesTaskResultRef() {
    stubTask("t1", new RunResult("r1", "rv"));
    WorkflowRunEntity run = run(str("ref", "$(tasks.t1.results.r1)"));
    parameterManager.resolveParamLayers(run, Optional.empty());
    assertEquals("rv", resolved(run, "ref"));
  }

  // (e, trailing json-path): $(tasks.<name>.results.<result>.<jsonpath>).
  @Test
  void resolvesTaskResultRefWithJsonPath() {
    stubTask("t1", new RunResult("r2", Map.of("field", "fv")));
    WorkflowRunEntity run = run(str("ref", "$(tasks.t1.results.r2.field)"));
    parameterManager.resolveParamLayers(run, Optional.empty());
    assertEquals("fv", resolved(run, "ref"));
  }

  // A reference to a missing task result is left verbatim (passthrough).
  @Test
  void passesThroughUnknownTaskResult() {
    when(taskRunRepository.findFirstByNameAndWorkflowRunRef(eq("missing"), eq(WF)))
        .thenReturn(Optional.empty());
    WorkflowRunEntity run = run(str("ref", "$(tasks.missing.results.x)"));
    parameterManager.resolveParamLayers(run, Optional.empty());
    assertEquals("$(tasks.missing.results.x)", resolved(run, "ref"));
  }

  // Matching is case-insensitive (ruled 2026-08-26): a reference resolves a param whose declared
  // name differs only in case. Definition-side validation rejects case-variant duplicates.
  @Test
  void resolvesParamCaseInsensitively() {
    WorkflowRunEntity run = run(str("MyParam", "hello"), str("ref", "$(params.myparam)"));
    parameterManager.resolveParamLayers(run, Optional.empty());
    assertEquals("hello", resolved(run, "ref"));
  }

  @Test
  void resolvesScopedParamCaseInsensitively() {
    WorkflowRunEntity run = run(str("ref", "$(GLOBAL.params.G1)"));
    run.getAnnotations().put("boomerang.io/global-params", Map.of("g1", "gv"));
    parameterManager.resolveParamLayers(run, Optional.empty());
    assertEquals("gv", resolved(run, "ref"));
  }

  // A plain string with no reference is unchanged.
  @Test
  void passesThroughPlainString() {
    WorkflowRunEntity run = run(str("ref", "just text"));
    parameterManager.resolveParamLayers(run, Optional.empty());
    assertEquals("just text", resolved(run, "ref"));
  }

  // Spec string fields (script/command/arguments/envs) get the same $(params.x) substitution as
  // param values, engine-side, so behaviour is identical on every executor (Tekton previously did
  // this in its controller; Kubernetes Jobs and Docker have no equivalent).
  @Test
  void resolvesSpecFieldsForTaskRun() {
    WorkflowRunEntity run = run();
    TaskRunEntity task = new TaskRunEntity();
    task.setParams(List.of(str("greeting", "hello")));
    task.getSpec().setScript("#!/bin/sh\necho $(params.greeting)");
    task.getSpec().setCommand(List.of("run", "$(params.greeting)"));
    task.getSpec().setArguments(List.of("--msg", "$(params.greeting)"));
    task.getSpec().setEnvs(List.of(new TaskEnvVar("GREETING", "$(params.greeting)")));

    parameterManager.resolveParamLayers(run, Optional.of(task));

    assertEquals("#!/bin/sh\necho hello", task.getSpec().getScript());
    assertEquals(List.of("run", "hello"), task.getSpec().getCommand());
    assertEquals(List.of("--msg", "hello"), task.getSpec().getArguments());
    assertEquals("hello", task.getSpec().getEnvs().get(0).getValue());
  }

  // Null spec fields survive substitution untouched; unresolved references stay verbatim.
  @Test
  void resolvesSpecFieldsNullSafeAndPassthrough() {
    WorkflowRunEntity run = run();
    TaskRunEntity task = new TaskRunEntity();
    task.setParams(List.of());
    task.getSpec().setCommand(List.of("echo", "$(params.unknown)"));

    parameterManager.resolveParamLayers(run, Optional.of(task));

    assertEquals(null, task.getSpec().getScript());
    assertEquals(null, task.getSpec().getArguments());
    assertEquals(List.of("echo", "$(params.unknown)"), task.getSpec().getCommand());
  }

  // ---------------------------------------------------------------------------------------------
  // boomerang-io/flow#439: a replacement containing a quote, a newline or a backslash used to be
  // spliced unescaped into a JSON-encoded copy of the value, the re-parse failed, and the param
  // resolved to null. Every case below must resolve to exactly the input value, byte for byte.
  // ---------------------------------------------------------------------------------------------

  @Test
  void resolvesValueContainingADoubleQuote() {
    String value = "He said \"hello\" and left";
    WorkflowRunEntity run = run(str("src", value), str("ref", "$(params.src)"));
    parameterManager.resolveParamLayers(run, Optional.empty());
    assertEquals(value, resolved(run, "ref"));
  }

  @Test
  void resolvesValueContainingADoubleQuoteInsideALargerString() {
    WorkflowRunEntity run =
        run(str("src", "a \"quoted\" word"), str("ref", "before $(params.src) after"));
    parameterManager.resolveParamLayers(run, Optional.empty());
    assertEquals("before a \"quoted\" word after", resolved(run, "ref"));
  }

  @Test
  void resolvesMultiLineValue() {
    String value = "line one\nline two\nline three";
    WorkflowRunEntity run = run(str("src", value), str("ref", "$(params.src)"));
    parameterManager.resolveParamLayers(run, Optional.empty());
    assertEquals(value, resolved(run, "ref"));
  }

  @Test
  void resolvesValueWithQuotesNewlinesAndABackslash() {
    String value = "copy C:\\tmp\\out then say \"done\"\nand stop";
    WorkflowRunEntity run = run(str("src", value), str("ref", "$(params.src)"));
    parameterManager.resolveParamLayers(run, Optional.empty());
    assertEquals(value, resolved(run, "ref"));
  }

  @Test
  void resolvesValueWithWindowsLineEndings() {
    String value = "first\r\nsecond\r\n";
    WorkflowRunEntity run = run(str("src", value), str("ref", "$(params.src)"));
    parameterManager.resolveParamLayers(run, Optional.empty());
    assertEquals(value, resolved(run, "ref"));
  }

  @Test
  void resolvesValueContainingALiteralDollar() {
    String value = "cost is $5 per unit";
    WorkflowRunEntity run = run(str("src", value), str("ref", "$(params.src)"));
    parameterManager.resolveParamLayers(run, Optional.empty());
    assertEquals(value, resolved(run, "ref"));
  }

  // A replacement that itself contains a $( sequence is inserted, and the sequence is left alone
  // because nothing in the parameter layers is named after it - no second substitution pass on it.
  @Test
  void resolvesValueContainingADollarParenSequence() {
    String value = "echo $(date) > /tmp/now";
    WorkflowRunEntity run = run(str("src", value), str("ref", "$(params.src)"));
    parameterManager.resolveParamLayers(run, Optional.empty());
    assertEquals(value, resolved(run, "ref"));
  }

  // A value that resolves to itself cannot terminate; the fallback is the original value, not null.
  @Test
  void selfReferencingValueFallsBackToTheOriginalValue() {
    WorkflowRunEntity run = run(str("loop", "$(params.loop)"));
    parameterManager.resolveParamLayers(run, Optional.empty());
    assertEquals("$(params.loop)", resolved(run, "loop"));
  }

  // The realistic "pass a body through" case: a JSON document carried in a string param.
  @Test
  void resolvesJsonDocumentValue() {
    String json = "{\"name\":\"widget\",\"tags\":[\"a\",\"b\"],\"nested\":{\"count\":1}}";
    WorkflowRunEntity run = run(str("body", json), str("ref", "$(params.body)"));
    parameterManager.resolveParamLayers(run, Optional.empty());
    assertEquals(json, resolved(run, "ref"));
  }

  // The case from the issue, end to end: a multi-line prompt with embedded quotes.
  @Test
  void resolvesMultiLinePromptWithEmbeddedQuotes() {
    String prompt =
        "You are a helpful assistant.\n\n"
            + "Summarise the text between the markers.\n"
            + "\"The quick brown fox jumps over the lazy dog.\"\n"
            + "Answer as JSON: {\"summary\": \"...\"}\n";
    WorkflowRunEntity run = run(str("prompt", prompt), str("ref", "$(params.prompt)"));
    parameterManager.resolveParamLayers(run, Optional.empty());
    assertEquals(prompt, resolved(run, "ref"));
  }

  // Multiple references in one value, each carrying a quote and a newline.
  @Test
  void resolvesMultipleReferencesInOneValue() {
    WorkflowRunEntity run =
        run(
            str("a", "one \"1\"\n"),
            str("b", "two \"2\"\n"),
            str("ref", "[$(params.a)|$(params.b)]"));
    parameterManager.resolveParamLayers(run, Optional.empty());
    assertEquals("[one \"1\"\n|two \"2\"\n]", resolved(run, "ref"));
  }

  // A task result carrying a trailing newline - what `echo` writes - used to null the param.
  @Test
  void resolvesTaskResultValueWithATrailingNewline() {
    stubTask("lookup", new RunResult("ref", "65b0a1f2c3d4e5f60718293a\n"));
    WorkflowRunEntity run = run(str("workflowRef", "$(tasks.lookup.results.ref)"));
    parameterManager.resolveParamLayers(run, Optional.empty());
    // A null here is what TaskExecutionService.runWorkflow reports three components away as
    // "Parameter 'workflowRef' resolved to no value" (TaskExecutionService.java:828-832).
    assertEquals("65b0a1f2c3d4e5f60718293a\n", resolved(run, "workflowRef"));
  }

  // Judgement call (boomerang-io/flow#439): a Map or List interpolated INTO a larger string is
  // rendered as JSON, not as Java's Map.toString() ("{k=v}"). A token that is the whole value of
  // an object-typed param still returns the structure itself, untouched - see below.
  @Test
  void rendersAStructuredReplacementAsJsonWhenInterpolatedIntoAString() {
    WorkflowRunEntity run =
        run(object("obj", Map.of("k", "v")), str("ref", "payload: $(params.obj)"));
    parameterManager.resolveParamLayers(run, Optional.empty());
    assertEquals("payload: {\"k\":\"v\"}", resolved(run, "ref"));
  }

  @Test
  void rendersAListReplacementAsJsonWhenInterpolatedIntoAString() {
    WorkflowRunEntity run =
        run(object("list", List.of("a", "b")), str("ref", "items=$(params.list)"));
    parameterManager.resolveParamLayers(run, Optional.empty());
    assertEquals("items=[\"a\",\"b\"]", resolved(run, "ref"));
  }

  // An object-typed param whose value IS a single reference keeps the structure - the one case
  // resolveParam returns the referenced value instead of substituting into string leaves.
  @Test
  void objectTypedParamKeepsTheStructureOfItsReplacement() {
    WorkflowRunEntity run =
        run(object("src", Map.of("k", "v")), object("ref", "$(params.src)"));
    parameterManager.resolveParamLayers(run, Optional.empty());
    assertEquals(Map.of("k", "v"), value(run, "ref"));
  }

  // ---------------------------------------------------------------------------------------------
  // An object-typed param whose value is a STRUCTURE containing references used to resolve to the
  // first reference's value alone: resolveParam returned on the first match for any object param,
  // so every other key, every literal around the reference, and the object shape itself were
  // dropped. The short-circuit now applies only to a value that is exactly one reference, and
  // everything else walks the structure through replaceStringInObject.
  // ---------------------------------------------------------------------------------------------

  // The reported shape: a reference with a literal suffix beside an unrelated key.
  @Test
  void objectParamKeepsEveryKeyWhenAReferenceHasSurroundingText() {
    WorkflowRunEntity run =
        run(
            str("host", "https://example.com"),
            object("cfg", Map.of("url", "$(params.host)/api", "retries", 3)));
    parameterManager.resolveParamLayers(run, Optional.empty());
    assertEquals(Map.of("url", "https://example.com/api", "retries", 3), value(run, "cfg"));
  }

  // Surrounding whitespace does not make a value "text around a reference": an object-typed param
  // still resolves to the structure, because a structure cannot carry the spaces anyway.
  @Test
  void objectTypedParamKeepsTheStructureDespiteSurroundingWhitespace() {
    WorkflowRunEntity run =
        run(object("src", Map.of("k", "v")), object("ref", "  $(params.src)  "));
    parameterManager.resolveParamLayers(run, Optional.empty());
    assertEquals(Map.of("k", "v"), value(run, "ref"));
  }

  // Two references in different leaves both resolve - the old short-circuit returned on the first.
  @Test
  void objectParamResolvesAReferenceInEveryLeaf() {
    WorkflowRunEntity run =
        run(
            str("host", "example.com"),
            str("port", "8443"),
            object(
                "cfg",
                Map.of("host", "$(params.host)", "address", "$(params.host):$(params.port)")));
    parameterManager.resolveParamLayers(run, Optional.empty());
    assertEquals(Map.of("host", "example.com", "address", "example.com:8443"), value(run, "cfg"));
  }

  // A reference two levels down: a Map inside a List inside a Map.
  @Test
  void objectParamResolvesAReferenceNestedTwoLevelsDeep() {
    WorkflowRunEntity run =
        run(
            str("host", "example.com"),
            object("cfg", Map.of("servers", List.of(Map.of("url", "https://$(params.host)/v1")))));
    parameterManager.resolveParamLayers(run, Optional.empty());
    assertEquals(
        Map.of("servers", List.of(Map.of("url", "https://example.com/v1"))), value(run, "cfg"));
  }

  // Map KEYS are substituted too, the same as they were under the whole-document substitution.
  @Test
  void objectParamResolvesAReferenceInAMapKey() {
    WorkflowRunEntity run =
        run(str("env", "production"), object("cfg", Map.of("$(params.env)", "on")));
    parameterManager.resolveParamLayers(run, Optional.empty());
    assertEquals(Map.of("production", "on"), value(run, "cfg"));
  }

  // A leaf that is itself a whole reference to an object renders as JSON, the same rule a
  // structured replacement takes anywhere inside a string. Only the param's OWN value being
  // exactly one reference returns the structure.
  @Test
  void objectParamRendersAStructuredLeafReplacementAsJson() {
    WorkflowRunEntity run =
        run(object("src", Map.of("k", "v")), object("cfg", Map.of("nested", "$(params.src)")));
    parameterManager.resolveParamLayers(run, Optional.empty());
    assertEquals(Map.of("nested", "{\"k\":\"v\"}"), value(run, "cfg"));
  }

  // A List-valued object param is walked as well as a Map-valued one.
  @Test
  void objectParamResolvesAReferenceInsideAList() {
    WorkflowRunEntity run =
        run(str("host", "example.com"), object("hosts", List.of("$(params.host):80", "localhost")));
    parameterManager.resolveParamLayers(run, Optional.empty());
    assertEquals(List.of("example.com:80", "localhost"), value(run, "hosts"));
  }

  // Spec fields take the same literal value: this is what the dispatcher writes into the
  // container's script and environment.
  @Test
  void resolvesQuotedMultiLineValueIntoSpecFields() {
    String prompt = "say \"hello\"\nthen stop";
    WorkflowRunEntity run = run();
    TaskRunEntity task = new TaskRunEntity();
    task.setParams(List.of(str("prompt", prompt)));
    task.getSpec().setScript("#!/bin/sh\ncat <<'EOF'\n$(params.prompt)\nEOF");
    task.getSpec().setEnvs(List.of(new TaskEnvVar("PROMPT", "$(params.prompt)")));

    parameterManager.resolveParamLayers(run, Optional.of(task));

    assertEquals("#!/bin/sh\ncat <<'EOF'\n" + prompt + "\nEOF", task.getSpec().getScript());
    assertEquals(prompt, task.getSpec().getEnvs().get(0).getValue());
  }

  private void stubTask(String name, RunResult result) {
    TaskRunEntity task = new TaskRunEntity();
    task.setResults(List.of(result));
    when(taskRunRepository.findFirstByNameAndWorkflowRunRef(eq(name), eq(WF)))
        .thenReturn(Optional.of(task));
  }

  private WorkflowRunEntity run(RunParam... params) {
    WorkflowRunEntity run = new WorkflowRunEntity();
    run.setId(WF);
    run.setParams(List.of(params));
    return run;
  }

  private static RunParam str(String name, String value) {
    return new RunParam(name, value, ParamType.string);
  }

  private static RunParam object(String name, Object value) {
    return new RunParam(name, value, ParamType.object);
  }

  private static Object value(WorkflowRunEntity run, String name) {
    return run.getParams().stream()
        .filter(p -> name.equals(p.getName()))
        .findFirst()
        .orElseThrow()
        .getValue();
  }

  private static String resolved(WorkflowRunEntity run, String name) {
    return (String) value(run, name);
  }
}
