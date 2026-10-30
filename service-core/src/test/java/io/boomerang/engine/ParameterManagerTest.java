package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRevisionEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.ParamType;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.ParamLayers;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.TaskEnvVar;
import io.boomerang.common.model.TaskRunSpec;
import io.boomerang.engine.repository.TaskRunRepository;
import io.boomerang.engine.repository.WorkflowRunRepository;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.ResultSpec;
import io.boomerang.common.model.TaskSpec;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.workflow.ParamLayerService;
import io.boomerang.workflow.TaskService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
  private ParamLayerService paramLayerService;
  private TaskService taskService;
  // What the stores hold for this test: ParamLayerService returns fresh copies on every call, as
  // it reads them from their stores each time.
  private final Map<String, Object> globalStore = new HashMap<>();
  private final Map<String, Object> workspaceStore = new HashMap<>();
  private final Map<String, Object> contextStore = new HashMap<>();
  // The names the global and workspace stores type as password.
  private final Set<String> globalSecrets = new HashSet<>();
  private final Set<String> workspaceSecrets = new HashSet<>();

  @BeforeEach
  void setUp() {
    taskRunRepository = mock(TaskRunRepository.class);
    paramLayerService = mock(ParamLayerService.class);
    taskService = mock(TaskService.class);
    when(paramLayerService.buildParamLayers(any(), any()))
        .thenAnswer(
            invocation -> {
              ParamLayers layers = new ParamLayers();
              layers.setGlobalParams(new HashMap<>(globalStore));
              layers.setWorkspaceParams(new HashMap<>(workspaceStore));
              layers.setContextParams(new HashMap<>(contextStore));
              layers.setSecretGlobalParams(new HashSet<>(globalSecrets));
              layers.setSecretWorkspaceParams(new HashSet<>(workspaceSecrets));
              return layers;
            });
    parameterManager =
        new ParameterManager(
            mock(WorkflowRunRepository.class),
            taskRunRepository,
            new ObjectMapper(),
            paramLayerService,
            taskService);
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
    globalStore.put("g1", "gv");
    parameterManager.resolveParamLayers(run, Optional.empty());
    assertEquals("gv", resolved(run, "ref"));
  }

  // Workspace parameters resolve as $(workspace.params.<name>) and, until the next major version, as the
  // deprecated $(team.params.<name>).
  @Test
  void resolvesWorkspaceScopeAndItsDeprecatedTeamSpelling() {
    WorkflowRunEntity run =
        run(str("current", "$(workspace.params.w1)"), str("deprecated", "$(team.params.w1)"));
    workspaceStore.put("w1", "wv");
    parameterManager.resolveParamLayers(run, Optional.empty());
    assertEquals("wv", resolved(run, "current"));
    assertEquals("wv", resolved(run, "deprecated"));
  }

  // (d) scoped object-path: $(<scope>.params.<name>.<jsonpath>).
  @Test
  void resolvesScopedObjectPathParam() {
    WorkflowRunEntity run = run(str("ref", "$(context.params.co.k)"));
    contextStore.put("co", Map.of("k", "cv"));
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
    globalStore.put("g1", "gv");
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
    TaskRunSpec spec = parameterManager.fillSpec(run, task);

    assertEquals("#!/bin/sh\necho hello", spec.getScript());
    assertEquals(List.of("run", "hello"), spec.getCommand());
    assertEquals(List.of("--msg", "hello"), spec.getArguments());
    assertEquals("hello", spec.getEnvs().get(0).getValue());
    // The stored spec is untouched: admission resolves the params only.
    assertEquals("#!/bin/sh\necho $(params.greeting)", task.getSpec().getScript());
    assertEquals("$(params.greeting)", task.getSpec().getEnvs().get(0).getValue());
  }

  // The spec reads the params as resolved: $(params.p) gets p's value, even when p was written as a
  // reference to an upstream task's result.
  @Test
  void specReadsAParamWhoseValueIsATaskResult() {
    stubTask("t1", new RunResult("r1", "RV"));
    WorkflowRunEntity run = run();
    TaskRunEntity task = new TaskRunEntity();
    task.setParams(List.of(str("p", "$(tasks.t1.results.r1)")));
    task.getSpec().setScript("echo $(params.p)");
    task.getSpec().setEnvs(List.of(new TaskEnvVar("P", "$(params.p)")));

    parameterManager.resolveParamLayers(run, Optional.of(task));
    TaskRunSpec spec = parameterManager.fillSpec(run, task);

    assertEquals("RV", task.getParams().get(0).getValue());
    assertEquals("echo RV", spec.getScript());
    assertEquals("RV", spec.getEnvs().get(0).getValue());
  }

  // A result reached through a param is still a task's output: it reaches the spec as written, so
  // a reference inside it is not expanded on the second pass.
  @Test
  void aResultReachedThroughAParamReachesTheSpecAsWritten() {
    workspaceStore.put("w1", "wv");
    stubTask("t1", new RunResult("r1", "says $(workspace.params.w1)"));
    WorkflowRunEntity run = run();
    TaskRunEntity task = new TaskRunEntity();
    task.setParams(List.of(str("p", "$(tasks.t1.results.r1)")));
    task.getSpec().setScript("echo $(params.p)");

    parameterManager.resolveParamLayers(run, Optional.of(task));
    TaskRunSpec spec = parameterManager.fillSpec(run, task);

    assertEquals("echo says $(workspace.params.w1)", spec.getScript());
  }

  // A task param reads the layers it inherits, never a sibling: p's $(params.q) is the workflow's
  // q. The spec reads the task's own params, so its $(params.q) is the task's q.
  @Test
  void aTaskParamReadsTheWorkflowLayerAndTheSpecReadsTheTask() {
    workspaceStore.put("w1", "wv");
    WorkflowRunEntity run = run(str("q", "from-workflow"));
    TaskRunEntity task = new TaskRunEntity();
    task.setParams(List.of(str("q", "$(workspace.params.w1)"), str("p", "hi $(params.q)")));
    task.getSpec().setScript("echo $(params.p) $(params.q)");

    parameterManager.resolveParamLayers(run, Optional.of(task));
    TaskRunSpec spec = parameterManager.fillSpec(run, task);

    assertEquals("hi from-workflow", taskValue(task, "p"));
    assertEquals("echo hi from-workflow wv", spec.getScript());
  }

  // A task param is frozen at admission; a workspace or global reference written straight into
  // the spec is read when the spec is filled in, so an edit between the two shows (the accepted
  // drift).
  @Test
  void fillSpecFreezesTaskParamsAndReadsScopedReferencesLive() {
    workspaceStore.put("w1", "at-admission");
    WorkflowRunEntity run = run();
    TaskRunEntity task = new TaskRunEntity();
    task.setParams(List.of(str("p", "$(workspace.params.w1)")));
    task.getSpec().setScript("echo $(params.p) $(workspace.params.w1)");

    parameterManager.resolveParamLayers(run, Optional.of(task));
    workspaceStore.put("w1", "at-claim");
    TaskRunSpec spec = parameterManager.fillSpec(run, task);

    assertEquals("echo at-admission at-claim", spec.getScript());
  }

  // Null spec fields survive substitution untouched; shell command substitution stays text.
  @Test
  void resolvesSpecFieldsNullSafeAndPassthrough() {
    WorkflowRunEntity run = run();
    TaskRunEntity task = new TaskRunEntity();
    task.setParams(List.of());
    task.getSpec().setCommand(List.of("echo", "$(date)"));

    assertEquals(Map.of(), parameterManager.resolveParamLayers(run, Optional.of(task)));
    TaskRunSpec spec = parameterManager.fillSpec(run, task);

    assertEquals(null, spec.getScript());
    assertEquals(null, spec.getArguments());
    assertEquals(List.of("echo", "$(date)"), spec.getCommand());
  }

  // ── Flow references: undeclared ones are reported for admission, declared ones without a value
  // resolve to an empty string ──────────────────────────────────────────────────────────────────

  // Each Flow reference naming nothing declared is reported under its field with why; shell command
  // substitution is not a Flow reference and is never reported.
  @Test
  void reportsUndeclaredFlowReferencesByFieldAndIgnoresShellSubstitution() {
    WorkflowRunEntity run = run();
    TaskRunEntity task = new TaskRunEntity();
    task.setName("deploy");
    task.setParams(List.of(str("registryToken", "t"), str("url", "$(workflow.params.hots)/api")));
    task.getSpec().setScript("login $(params.registryTokn) $(date) $(cat /etc/hostname)");
    task.getSpec().setArguments(List.of("$(params.registryToken)", "$(git rev-parse HEAD)"));
    task.getSpec().setEnvs(List.of(new TaskEnvVar("R", "$(global.params.missing)")));

    Map<String, Set<String>> unresolved = parameterManager.resolveParamLayers(run, Optional.of(task));

    assertEquals(
        Map.of(
            "param 'url'", Set.of("$(workflow.params.hots): no such workflow param 'hots'"),
            "script", Set.of("$(params.registryTokn): no such param 'registryTokn'"),
            "env 'R'", Set.of("$(global.params.missing): no such global param 'missing'")),
        unresolved);
    assertEquals(
        "Task 'deploy': param 'url' references $(workflow.params.hots): no such workflow param"
            + " 'hots'; script references $(params.registryTokn): no such param 'registryTokn';"
            + " env 'R' references $(global.params.missing): no such global param 'missing'",
        ParameterManager.unresolvedMessage("deploy", unresolved));
  }

  // Declared but without a value is an empty string: an optional param, an object path that matches
  // nothing on a declared object, a result path that matches nothing. An object path on an
  // undeclared param is reported.
  @Test
  void aDeclaredReferenceWithoutAValueIsAnEmptyString() {
    stubTask("t1", new RunResult("r1", Map.of("field", "fv")));
    WorkflowRunEntity run =
        run(object("cfg", Map.of("region", "eu")), new RunParam("optional", null, ParamType.string));
    TaskRunEntity task = new TaskRunEntity();
    task.setParams(
        List.of(
            str("ok", "$(params.cfg.region) $(tasks.t1.results.r1.field)"),
            str("empty", "[$(params.cfg.zone)][$(tasks.t1.results.r1.nope)][$(params.optional)]"),
            str("bad", "$(params.nocfg.zone)")));

    Map<String, Set<String>> unresolved = parameterManager.resolveParamLayers(run, Optional.of(task));

    assertEquals(
        Map.of("param 'bad'", Set.of("$(params.nocfg.zone): no such param 'nocfg'")), unresolved);
    assertEquals("eu fv", taskValue(task, "ok"));
    assertEquals("[][][]", taskValue(task, "empty"));
    assertEquals("$(params.nocfg.zone)", taskValue(task, "bad"));
  }

  // At admission the spec check shares the params' upstream lookups: a task result referenced in
  // both is read once, and its task's declared results once.
  @Test
  void admissionLooksUpAnUpstreamTaskOnceForParamsAndSpec() {
    WorkflowRevisionEntity revision = new WorkflowRevisionEntity();
    revision.setTasks(List.of(node("build-a", "builder"), node("say", "echoer")));
    when(paramLayerService.getRevision("rev1")).thenReturn(revision);
    when(taskService.getSpecs(any())).thenReturn(Map.of());
    stubTaskRun("build-a", "builder", RunStatus.succeeded, new RunResult("url", "https://a"));
    WorkflowRunEntity run = run();
    run.setWorkflowRevisionRef("rev1");
    TaskRunEntity task = new TaskRunEntity();
    task.setParams(List.of(str("target", "$(tasks.build-a.results.url)")));
    task.getSpec().setScript("#!/bin/sh\ncurl $(tasks.build-a.results.url)");

    parameterManager.resolveParamLayers(run, Optional.of(task));

    assertEquals("https://a", taskValue(task, "target"));
    verify(taskRunRepository, times(1)).findFirstByNameAndWorkflowRunRef(eq("build-a"), eq(WF));
    verify(taskService, times(1)).getSpecs(any());
  }

  // $(tasks.t.results.r) is declared when t is a task in the run's workflow revision and r is a
  // result its catalogue task declares; a task that declares none may be asked for any result. A
  // skipped task's declared result is an empty string.
  @Test
  void aTaskResultIsCheckedAgainstTheRevisionAndTheDeclaredResults() {
    WorkflowRevisionEntity revision = new WorkflowRevisionEntity();
    revision.setTasks(
        List.of(node("build-a", "builder"), node("build-b", "builder"), node("say", "echoer")));
    when(paramLayerService.getRevision("rev1")).thenReturn(revision);
    TaskSpec builder = new TaskSpec();
    ResultSpec url = new ResultSpec();
    url.setName("url");
    builder.setResults(List.of(url));
    when(taskService.getSpecs(any()))
        .thenAnswer(
            invocation -> {
              Set<TaskService.TaskRef> refs = invocation.getArgument(0);
              Map<TaskService.TaskRef, TaskSpec> specs = new HashMap<>();
              refs.forEach(r -> specs.put(r, "builder".equals(r.ref()) ? builder : new TaskSpec()));
              return specs;
            });
    stubTaskRun("build-a", "builder", RunStatus.succeeded, new RunResult("url", "https://a"));
    stubTaskRun("build-b", "builder", RunStatus.skipped, new RunResult("url", null));
    stubTaskRun("say", "echoer", RunStatus.succeeded, new RunResult("anything", "said"));
    WorkflowRunEntity run = run();
    run.setWorkflowRevisionRef("rev1");
    TaskRunEntity task = new TaskRunEntity();
    task.setParams(
        List.of(
            str("join", "[$(tasks.build-a.results.url)][$(tasks.build-b.results.url)]"),
            str("free", "[$(tasks.say.results.anything)][$(tasks.say.results.other)]"),
            str("bad", "$(tasks.biuld-a.results.url) $(tasks.build-a.results.uri)")));

    Map<String, Set<String>> unresolved = parameterManager.resolveParamLayers(run, Optional.of(task));

    assertEquals(
        Map.of(
            "param 'bad'",
            Set.of(
                "$(tasks.biuld-a.results.url): task 'biuld-a' is not in this workflow",
                "$(tasks.build-a.results.uri): task 'build-a' declares no result 'uri'")),
        unresolved);
    assertEquals("[https://a][]", taskValue(task, "join"));
    assertEquals("[said][]", taskValue(task, "free"));
  }

  // A run's own params are not checked: a reference naming nothing declared is left as written.
  @Test
  void aWorkflowRunParamIsNotChecked() {
    WorkflowRunEntity run = run(str("ref", "$(params.nothing)"));
    assertEquals(Map.of(), parameterManager.resolveParamLayers(run, Optional.empty()));
    assertEquals("$(params.nothing)", resolved(run, "ref"));
  }

  // Admission checked the spec; a global deleted before the claim fails the fill-in, naming it.
  @Test
  void fillSpecThrowsWhenAReferenceNoLongerResolves() {
    globalStore.put("g1", "here");
    WorkflowRunEntity run = run();
    TaskRunEntity task = new TaskRunEntity();
    task.setName("deploy");
    task.setParams(List.of());
    task.getSpec().setScript("echo $(global.params.g1)");
    assertEquals(Map.of(), parameterManager.resolveParamLayers(run, Optional.of(task)));

    globalStore.remove("g1");
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> parameterManager.fillSpec(run, task));
    assertEquals(
        "Task 'deploy': script references $(global.params.g1): no such global param 'g1'",
        e.getMessage());
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
    TaskRunSpec spec = parameterManager.fillSpec(run, task);

    assertEquals("#!/bin/sh\ncat <<'EOF'\n" + prompt + "\nEOF", spec.getScript());
    assertEquals(prompt, spec.getEnvs().get(0).getValue());
  }

  // ── layers read from their stores; values supplied or produced are inserted as written ─────────

  // The layers come from ParamLayerService for the run's workflow - whose owner is the run's
  // workspace - and the revision it runs; nothing is read from the run's annotations.
  @Test
  void readsTheLayersForTheRunsWorkflowAndRevision() {
    WorkflowRevisionEntity revision = new WorkflowRevisionEntity();
    when(paramLayerService.getRevision("rev-ref")).thenReturn(revision);
    WorkflowRunEntity run = run(str("ref", "$(workspace.params.w1)"));
    run.setWorkflowRef("wf-ref");
    run.setWorkflowRevisionRef("rev-ref");
    run.getAnnotations().put("boomerang.io/workspace-params", Map.of("w1", "stale snapshot"));
    workspaceStore.put("w1", "current");

    parameterManager.resolveParamLayers(run, Optional.empty());

    assertEquals("current", resolved(run, "ref"));
    verify(paramLayerService).buildParamLayers(eq("wf-ref"), eq(revision));
  }

  // A value a task produced is inserted as written: a reference inside it is not expanded, so a
  // task's output cannot pull a workspace value into a later task.
  @Test
  void insertsTaskResultsAsWritten() {
    workspaceStore.put("w1", "wv");
    stubTask("t1", new RunResult("r1", "says $(workspace.params.w1) and $(workflowrun-ref)"));
    WorkflowRunEntity run = run(str("ref", "got: $(tasks.t1.results.r1)"));
    TaskRunEntity task = new TaskRunEntity();
    task.setParams(List.of(str("p", "$(tasks.t1.results.r1)")));

    parameterManager.resolveParamLayers(run, Optional.of(task));

    assertEquals("says $(workspace.params.w1) and $(workflowrun-ref)", task.getParams().get(0).getValue());
  }

  // Configuration still composes configuration: a workspace value may reference a global one.
  @Test
  void workspaceValuesStillComposeGlobalValues() {
    globalStore.put("g1", "GV");
    workspaceStore.put("w2", "has $(global.params.g1) inside");
    WorkflowRunEntity run = run(str("ref", "$(workspace.params.w2)"));

    parameterManager.resolveParamLayers(run, Optional.empty());

    assertEquals("has GV inside", resolved(run, "ref"));
  }

  // A webhook's payload is kept as it arrived at run start, and inserted as written into a task.
  @Test
  void insertsWebhookPayloadParamsAsWritten() {
    workspaceStore.put("w1", "wv");
    WorkflowRunEntity run = run(str("data", "payload $(workspace.params.w1)"));
    run.setTrigger("webhook");

    parameterManager.resolveParamLayers(run, Optional.empty());
    assertEquals("payload $(workspace.params.w1)", resolved(run, "data"), "kept as it arrived");

    TaskRunEntity task = new TaskRunEntity();
    task.setParams(List.of(str("viaFlat", "$(params.data)"), str("viaScope", "$(workflow.params.data)")));
    parameterManager.resolveParamLayers(run, Optional.of(task));
    assertEquals("payload $(workspace.params.w1)", task.getParams().get(0).getValue());
    assertEquals("payload $(workspace.params.w1)", task.getParams().get(1).getValue());
  }

  // On a webhook run, a param still holding its revision default was written in the workspace and
  // resolves; only values that differ from it were supplied.
  @Test
  void resolvesAWebhookRunsDefaultValues() {
    workspaceStore.put("defaultBranch", "main");
    AbstractParam branch = new AbstractParam();
    branch.setName("branch");
    branch.setDefaultValue("$(workspace.params.defaultBranch)");
    WorkflowRevisionEntity revision = new WorkflowRevisionEntity();
    revision.setParams(List.of(branch));
    when(paramLayerService.getRevision("rev-ref")).thenReturn(revision);
    WorkflowRunEntity run = run(str("branch", "$(workspace.params.defaultBranch)"));
    run.setTrigger("webhook");
    run.setWorkflowRevisionRef("rev-ref");

    parameterManager.resolveParamLayers(run, Optional.empty());

    assertEquals("main", resolved(run, "branch"));
  }

  // People, schedules and authenticated API callers may reference parameters in the values they
  // give a run.
  @Test
  void resolvesValuesSuppliedOnAManualRun() {
    workspaceStore.put("w1", "wv");
    WorkflowRunEntity run = run(str("given", "$(workspace.params.w1)"));
    run.setTrigger("manual");

    parameterManager.resolveParamLayers(run, Optional.empty());

    assertEquals("wv", resolved(run, "given"));
  }

  // A child run's values were resolved by its parent and a retry's by the run it retries; neither
  // is expanded again.
  @Test
  void insertsChildAndRetryRunValuesAsWritten() {
    workspaceStore.put("w1", "wv");
    for (String trigger : List.of("task", "retry")) {
      WorkflowRunEntity run = run(str("carried", "literal $(workspace.params.w1)"));
      run.setTrigger(trigger);
      parameterManager.resolveParamLayers(run, Optional.empty());
      assertEquals("literal $(workspace.params.w1)", resolved(run, "carried"), trigger);
    }
  }

  // A task param reads the run's x even when the task has an x of its own, and a supplied x is
  // inserted as it arrived.
  @Test
  void aTaskParamReadsTheSuppliedRunParamNotASiblingOfTheSameName() {
    globalStore.put("g1", "GV");
    WorkflowRunEntity run = run(str("data", "supplied $(global.params.g1)"));
    run.setTrigger("webhook");
    TaskRunEntity task = new TaskRunEntity();
    task.setParams(List.of(str("data", "authored $(global.params.g1)"), str("ref", "$(params.data)")));

    parameterManager.resolveParamLayers(run, Optional.of(task));

    assertEquals("authored GV", task.getParams().get(0).getValue());
    assertEquals("supplied $(global.params.g1)", task.getParams().get(1).getValue());
  }

  // Escaping keeps a value's own "$$(" sequences as they were.
  @Test
  void keepsDoubledDollarSequencesInAnInsertedValue() {
    stubTask("t1", new RunResult("r1", "cost $$(5) and $(x)"));
    WorkflowRunEntity run = run();
    TaskRunEntity task = new TaskRunEntity();
    task.setParams(List.of(str("p", "[$(tasks.t1.results.r1)]")));

    parameterManager.resolveParamLayers(run, Optional.of(task));

    assertEquals("[cost $$(5) and $(x)]", task.getParams().get(0).getValue());
  }

  // A whole-value reference to a structured result returns the structure itself, untouched.
  @Test
  void returnsAWholeStructuredResultUnchanged() {
    Map<String, Object> structure = Map.of("text", "$(workspace.params.w1)");
    workspaceStore.put("w1", "wv");
    stubTask("t1", new RunResult("r1", structure));
    WorkflowRunEntity run = run();
    TaskRunEntity task = new TaskRunEntity();
    task.setParams(List.of(object("p", "$(tasks.t1.results.r1)")));

    parameterManager.resolveParamLayers(run, Optional.of(task));

    assertSame(structure, task.getParams().get(0).getValue());
  }

  // ── Taint: a string that takes in a secret becomes a secret ────────────────

  @Test
  void aStringParamThatSubstitutesASecretBecomesASecret() {
    WorkflowRunEntity run =
        run(
            secret("dbPassword", "s3cr3t-pa55"),
            str("dsn", "postgres://app:$(params.dbPassword)@db"),
            str("host", "db"),
            str("url", "https://$(params.host)"));
    parameterManager.resolveParamLayers(run, Optional.empty());

    assertEquals("postgres://app:s3cr3t-pa55@db", resolved(run, "dsn"));
    assertEquals(ParamType.secret, type(run, "dsn"));
    assertEquals("https://db", resolved(run, "url"));
    assertEquals(ParamType.string, type(run, "url"), "no secret in, no taint");
  }

  @Test
  void theScopedWorkflowReferenceTaintsToo() {
    WorkflowRunEntity run =
        run(secret("token", "tok-123456"), str("header", "Bearer $(workflow.params.token)"));
    parameterManager.resolveParamLayers(run, Optional.empty());

    assertEquals("Bearer tok-123456", resolved(run, "header"));
    assertEquals(ParamType.secret, type(run, "header"));
  }

  // Absent type is string, as it always was: substituted, and tainted like any string.
  @Test
  void anUntypedParamResolvesAsAStringAndCanBeTainted() {
    WorkflowRunEntity run =
        run(secret("pw", "hunter2-long"), new RunParam("plain", "x-$(params.pw)"));
    parameterManager.resolveParamLayers(run, Optional.empty());

    assertEquals("x-hunter2-long", resolved(run, "plain"));
    assertEquals(ParamType.secret, type(run, "plain"));
  }

  @Test
  void aSecretParamResolvesItsOwnReferencesAndStaysSecret() {
    WorkflowRunEntity run = run(str("user", "app"), secret("login", "$(params.user):pw-9999"));
    parameterManager.resolveParamLayers(run, Optional.empty());

    assertEquals("app:pw-9999", resolved(run, "login"));
    assertEquals(ParamType.secret, type(run, "login"));
  }

  // A secret is a string only: an array or object that takes one in keeps its type, and the
  // value scrub on the way out covers it.
  @Test
  void anArrayOrObjectThatSubstitutesASecretKeepsItsType() {
    WorkflowRunEntity run =
        run(
            secret("pw", "hunter2-long"),
            new RunParam("args", new java.util.ArrayList<>(List.of("--pw=$(params.pw)")), ParamType.array),
            object("cfg", Map.of("auth", "$(params.pw)")));
    parameterManager.resolveParamLayers(run, Optional.empty());

    assertEquals(List.of("--pw=hunter2-long"), value(run, "args"));
    assertEquals(ParamType.array, type(run, "args"));
    assertEquals(Map.of("auth", "hunter2-long"), value(run, "cfg"));
    assertEquals(ParamType.object, type(run, "cfg"));
  }

  // A task param referencing a workflow secret becomes a secret, and a task param of the same name
  // does not shadow it: the reference reads the workflow layer, so it carries the secret's taint.
  @Test
  void aTaskParamTakesTheTaintOfTheLayerItReads() {
    WorkflowRunEntity run = run(secret("apiKey", "sk-live-0001"), secret("shadowed", "wf-secret-1"));
    TaskRunEntity task = new TaskRunEntity();
    task.setParams(
        List.of(
            str("header", "X-Key: $(params.apiKey)"),
            str("shadowed", "task-value"),
            str("uses", "$(params.shadowed)")));

    parameterManager.resolveParamLayers(run, Optional.of(task));

    assertEquals("X-Key: sk-live-0001", taskValue(task, "header"));
    assertEquals(ParamType.secret, taskType(task, "header"));
    assertEquals("wf-secret-1", taskValue(task, "uses"));
    assertEquals(ParamType.secret, taskType(task, "uses"));
    assertEquals("task-value", taskValue(task, "shadowed"));
    assertEquals(ParamType.string, taskType(task, "shadowed"));
  }

  // The global and workspace layers are read from their typed stores, so a password-typed global or
  // workspace parameter is a secret: a string that takes one in becomes a secret, by its scoped and
  // its flat reference alike.
  @Test
  void aStringThatTakesInAPasswordGlobalOrWorkspaceParamBecomesASecret() {
    globalStore.put("registryToken", "glob-tok-0001");
    globalSecrets.add("registryToken");
    workspaceStore.put("dbPass", "ws-pass-0001");
    workspaceSecrets.add("dbPass");
    workspaceStore.put("region", "ap-southeast-2");
    WorkflowRunEntity run =
        run(
            str("auth", "Bearer $(global.params.registryToken)"),
            str("dsn", "pg://app:$(workspace.params.dbPass)@db"),
            str("legacy", "$(team.params.dbPass)"),
            str("flat", "$(params.registryToken)"),
            str("where", "$(workspace.params.region)"));
    parameterManager.resolveParamLayers(run, Optional.empty());

    assertEquals("Bearer glob-tok-0001", resolved(run, "auth"));
    assertEquals(ParamType.secret, type(run, "auth"));
    assertEquals(ParamType.secret, type(run, "dsn"));
    assertEquals(ParamType.secret, type(run, "legacy"));
    assertEquals(ParamType.secret, type(run, "flat"));
    assertEquals(ParamType.string, type(run, "where"), "a plain workspace value does not taint");
  }

  // Precedence follows the values: a workflow param of the same name wins the flat reference, and
  // carries its own type.
  @Test
  void aWorkflowParamShadowsAPasswordGlobalParamOfTheSameName() {
    globalStore.put("token", "glob-tok-0001");
    globalSecrets.add("token");
    WorkflowRunEntity run = run(str("token", "public"), str("uses", "$(params.token)"));
    parameterManager.resolveParamLayers(run, Optional.empty());

    assertEquals("public", resolved(run, "uses"));
    assertEquals(ParamType.string, type(run, "uses"));
  }

  // ---------------------------------------------------------------------------------------------
  // The layering contract. $(params.x) is the flattened view of the global, workspace, workflow and
  // context layers, each overriding the one before; $(<scope>.params.x) reads one layer. A task's
  // own params are not a layer: a task param's value reads the flattened layers, and only the
  // task's spec (script, command, arguments, env) reads the task's params.
  // ---------------------------------------------------------------------------------------------

  // Each layer overrides the one before it: workflow over workspace over global.
  @Test
  void aTaskParamReadsTheNearestOuterLayer() {
    globalStore.put("x", "global");
    workspaceStore.put("x", "workspace");
    globalStore.put("y", "global");
    workspaceStore.put("y", "workspace");
    globalStore.put("z", "global");
    WorkflowRunEntity run = run(str("x", "workflow"));
    TaskRunEntity task = new TaskRunEntity();
    task.setParams(List.of(str("a", "$(params.x)"), str("b", "$(params.y)"), str("c", "$(params.z)")));

    parameterManager.resolveParamLayers(run, Optional.of(task));

    assertEquals("workflow", taskValue(task, "a"));
    assertEquals("workspace", taskValue(task, "b"));
    assertEquals("global", taskValue(task, "c"));
  }

  // Context is the last layer flattened, so it overrides a workflow param of the same name.
  @Test
  void theContextLayerOverridesTheWorkflowLayer() {
    contextStore.put("x", "context");
    WorkflowRunEntity run = run(str("x", "workflow"));
    TaskRunEntity task = new TaskRunEntity();
    task.setParams(List.of(str("a", "$(params.x)"), str("b", "$(workflow.params.x)")));

    parameterManager.resolveParamLayers(run, Optional.of(task));

    assertEquals("context", taskValue(task, "a"));
    assertEquals("workflow", taskValue(task, "b"));
  }

  // A scope reaches its own layer past every override, the deprecated team spelling included.
  @Test
  void eachScopeReadsItsOwnLayer() {
    globalStore.put("x", "global");
    workspaceStore.put("x", "workspace");
    contextStore.put("x", "context");
    WorkflowRunEntity run = run(str("x", "workflow"));
    TaskRunEntity task = new TaskRunEntity();
    task.setParams(
        List.of(
            str("g", "$(global.params.x)"),
            str("w", "$(workspace.params.x)"),
            str("t", "$(team.params.x)"),
            str("f", "$(workflow.params.x)"),
            str("c", "$(context.params.x)")));

    parameterManager.resolveParamLayers(run, Optional.of(task));

    assertEquals("global", taskValue(task, "g"));
    assertEquals("workspace", taskValue(task, "w"));
    assertEquals("workspace", taskValue(task, "t"));
    assertEquals("workflow", taskValue(task, "f"));
    assertEquals("context", taskValue(task, "c"));
  }

  // The common authoring pattern: a task param named after the workflow param it passes through.
  @Test
  void aTaskParamNamedAfterAWorkflowParamPassesItThrough() {
    WorkflowRunEntity run = run(str("url", "https://example.com"), str("model", "m-1"));
    TaskRunEntity task = new TaskRunEntity();
    task.setParams(
        List.of(
            str("url", "$(params.url)"),
            str("model", "$(params.model)"),
            str("prompt", "Summarise $(params.url) with $(params.model)")));

    Map<String, Set<String>> unresolved = parameterManager.resolveParamLayers(run, Optional.of(task));

    assertEquals(Map.of(), unresolved);
    assertEquals("https://example.com", taskValue(task, "url"));
    assertEquals("m-1", taskValue(task, "model"));
    assertEquals("Summarise https://example.com with m-1", taskValue(task, "prompt"));
  }

  // A task param naming only a sibling names nothing in the layers, so it is reported.
  @Test
  void aTaskParamCannotReadASibling() {
    WorkflowRunEntity run = run();
    TaskRunEntity task = new TaskRunEntity();
    task.setParams(List.of(str("y", "b"), str("x", "$(params.y)-a")));

    Map<String, Set<String>> unresolved = parameterManager.resolveParamLayers(run, Optional.of(task));

    assertEquals(Map.of("param 'x'", Set.of("$(params.y): no such param 'y'")), unresolved);
    assertEquals("$(params.y)-a", taskValue(task, "x"));
  }

  // Two task params naming each other read the outer layers, so there is no cycle to refuse.
  @Test
  void taskParamsNamingEachOtherReadTheOuterLayers() {
    WorkflowRunEntity run = run(str("x", "wx"), str("y", "wy"));
    TaskRunEntity task = new TaskRunEntity();
    task.setParams(List.of(str("x", "$(params.y)"), str("y", "$(params.x)")));

    Map<String, Set<String>> unresolved = parameterManager.resolveParamLayers(run, Optional.of(task));

    assertEquals(Map.of(), unresolved);
    assertEquals("wy", taskValue(task, "x"));
    assertEquals("wx", taskValue(task, "y"));
  }

  // A for-each item's item and index are context the engine set at fan-out, so the item's own
  // params read them, as the item reads every other context param.
  @Test
  void aForeachItemsParamsReadItsItemAndIndex() {
    WorkflowRunEntity run = run();
    TaskRunEntity task = new TaskRunEntity();
    task.setParentRef("parent");
    task.setIndex(1);
    task.setParams(
        List.of(
            str("item", "beta"),
            str("index", "1"),
            str("script", "echo item=$(params.item) index=$(params.index)")));
    task.getSpec().setScript("echo $(params.script) $(params.item)");

    Map<String, Set<String>> unresolved = parameterManager.resolveParamLayers(run, Optional.of(task));

    assertEquals(Map.of(), unresolved);
    assertEquals("echo item=beta index=1", taskValue(task, "script"));
    assertEquals("beta", taskValue(task, "item"));
    assertEquals("echo echo item=beta index=1 beta", parameterManager.fillSpec(run, task).getScript());
  }

  // The spec reads the task's own params first (as Tekton does), and a scope still reaches past.
  @Test
  void theSpecReadsTheTasksOwnParamsAndAScopeReachesPast() {
    WorkflowRunEntity run = run(str("x", "workflow"));
    TaskRunEntity task = new TaskRunEntity();
    task.setParams(List.of(str("x", "task")));
    task.getSpec().setScript("echo $(params.x) $(workflow.params.x)");
    task.getSpec().setEnvs(new ArrayList<>(List.of(new TaskEnvVar("X", "$(params.x)"))));

    parameterManager.resolveParamLayers(run, Optional.of(task));
    TaskRunSpec spec = parameterManager.fillSpec(run, task);

    assertEquals("echo task workflow", spec.getScript());
    assertEquals("task", spec.getEnvs().get(0).getValue());
  }

  // Secrets follow the same layers as values: the taint is the winning layer's, never a task
  // sibling's. A pass-through by name is the case the layering exists for, so it must stay secret.
  @Test
  void aWorkflowSecretPassedThroughByNameStaysSecret() {
    WorkflowRunEntity run = run(secret("apiKey", "sk-live-0001"));
    TaskRunEntity task = new TaskRunEntity();
    task.setParams(List.of(str("apiKey", "$(params.apiKey)"), str("auth", "Bearer $(params.apiKey)")));

    parameterManager.resolveParamLayers(run, Optional.of(task));

    assertEquals("sk-live-0001", taskValue(task, "apiKey"));
    assertEquals(ParamType.secret, taskType(task, "apiKey"));
    assertEquals("Bearer sk-live-0001", taskValue(task, "auth"));
    assertEquals(ParamType.secret, taskType(task, "auth"));
  }

  // Password-typed global and workspace parameters taint a task param that reads them, by the
  // flattened name or by scope.
  @Test
  void globalAndWorkspaceSecretsPassedThroughByNameStaySecret() {
    globalStore.put("registryToken", "g-secret");
    globalSecrets.add("registryToken");
    workspaceStore.put("dbPass", "w-secret");
    workspaceSecrets.add("dbPass");
    WorkflowRunEntity run = run();
    TaskRunEntity task = new TaskRunEntity();
    task.setParams(
        List.of(
            str("registryToken", "$(params.registryToken)"),
            str("dbPass", "$(workspace.params.dbPass)")));

    parameterManager.resolveParamLayers(run, Optional.of(task));

    assertEquals("g-secret", taskValue(task, "registryToken"));
    assertEquals(ParamType.secret, taskType(task, "registryToken"));
    assertEquals("w-secret", taskValue(task, "dbPass"));
    assertEquals(ParamType.secret, taskType(task, "dbPass"));
  }

  // The taint is the winning layer's: a plain workflow x over a secret global x resolves plain,
  // and the scoped read of the global stays secret.
  @Test
  void theTaintFollowsTheLayerThatWins() {
    globalStore.put("x", "g-secret");
    globalSecrets.add("x");
    WorkflowRunEntity run = run(str("x", "plain"));
    TaskRunEntity task = new TaskRunEntity();
    task.setParams(List.of(str("x", "$(params.x)"), str("g", "$(global.params.x)")));

    parameterManager.resolveParamLayers(run, Optional.of(task));

    assertEquals("plain", taskValue(task, "x"));
    assertEquals(ParamType.string, taskType(task, "x"));
    assertEquals("g-secret", taskValue(task, "g"));
    assertEquals(ParamType.secret, taskType(task, "g"));
  }

  // A secret-typed task param keeps its type when it reads a plain value; the spec reads it.
  @Test
  void aSecretTypedTaskParamStaysSecretAndTheSpecReadsIt() {
    WorkflowRunEntity run = run(str("token", "from-workflow"));
    TaskRunEntity task = new TaskRunEntity();
    task.setParams(List.of(secret("token", "$(params.token)")));
    task.getSpec().setScript("login $(params.token)");

    parameterManager.resolveParamLayers(run, Optional.of(task));

    assertEquals("from-workflow", taskValue(task, "token"));
    assertEquals(ParamType.secret, taskType(task, "token"));
    assertEquals("login from-workflow", parameterManager.fillSpec(run, task).getScript());
  }

  // A passed-through secret reaches the spec only when it is filled in at claim: the stored spec
  // keeps the reference, so the value is never written into it.
  @Test
  void aPassedThroughSecretFillsTheSpecAtClaimAndIsNeverStoredInIt() {
    WorkflowRunEntity run = run(secret("apiKey", "sk-live-0001"));
    TaskRunEntity task = new TaskRunEntity();
    task.setParams(List.of(str("apiKey", "$(params.apiKey)")));
    task.getSpec().setEnvs(new ArrayList<>(List.of(new TaskEnvVar("API_KEY", "$(params.apiKey)"))));

    parameterManager.resolveParamLayers(run, Optional.of(task));

    assertEquals("$(params.apiKey)", task.getSpec().getEnvs().get(0).getValue());
    assertEquals("sk-live-0001", parameterManager.fillSpec(run, task).getEnvs().get(0).getValue());
  }

  // Text carrying a passed-through secret's value is recognised as secret, so it is never a label.
  @Test
  void textCarryingAPassedThroughSecretIsRecognised() {
    WorkflowRunEntity run = run(secret("apiKey", "sk-live-0001"));
    TaskRunEntity task = new TaskRunEntity();
    task.setParams(List.of(str("apiKey", "$(params.apiKey)")));

    parameterManager.resolveParamLayers(run, Optional.of(task));

    assertTrue(parameterManager.carriesSecret(run, task, "deployed with sk-live-0001"));
    assertFalse(parameterManager.carriesSecret(run, task, "deployed"));
  }

  // A run's own params resolve against the layers too: a workflow param reads the workspace's.
  @Test
  void aWorkflowParamReadsTheWorkspaceLayer() {
    workspaceStore.put("region", "eu");
    WorkflowRunEntity run = run(str("target", "deploy-$(params.region)"));

    parameterManager.resolveParamLayers(run, Optional.empty());

    assertEquals("deploy-eu", resolved(run, "target"));
  }

  private void stubTask(String name, RunResult result) {
    TaskRunEntity task = new TaskRunEntity();
    task.setResults(List.of(result));
    when(taskRunRepository.findFirstByNameAndWorkflowRunRef(eq(name), eq(WF)))
        .thenReturn(Optional.of(task));
  }

  private void stubTaskRun(String name, String taskRef, RunStatus status, RunResult result) {
    TaskRunEntity task = new TaskRunEntity();
    task.setName(name);
    task.setType(TaskType.template);
    task.setTaskRef(taskRef);
    task.setTaskVersion(1);
    task.setStatus(status);
    task.setResults(List.of(result));
    when(taskRunRepository.findFirstByNameAndWorkflowRunRef(eq(name), eq(WF)))
        .thenReturn(Optional.of(task));
  }

  private static WorkflowTask node(String name, String taskRef) {
    WorkflowTask node = new WorkflowTask();
    node.setName(name);
    node.setType(TaskType.template);
    node.setTaskRef(taskRef);
    node.setTaskVersion(1);
    return node;
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

  private static RunParam secret(String name, String value) {
    return new RunParam(name, value, ParamType.secret);
  }

  private static ParamType type(WorkflowRunEntity run, String name) {
    return run.getParams().stream()
        .filter(p -> name.equals(p.getName()))
        .findFirst()
        .orElseThrow()
        .getType();
  }

  private static Object taskValue(TaskRunEntity task, String name) {
    return task.getParams().stream()
        .filter(p -> name.equals(p.getName()))
        .findFirst()
        .orElseThrow()
        .getValue();
  }

  private static ParamType taskType(TaskRunEntity task, String name) {
    return task.getParams().stream()
        .filter(p -> name.equals(p.getName()))
        .findFirst()
        .orElseThrow()
        .getType();
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
