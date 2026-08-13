package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.boomerang.client.WorkflowClient;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.ParamType;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.engine.repository.TaskRunRepository;
import io.boomerang.engine.repository.WorkflowRunRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
            mock(WorkflowRunRepository.class), taskRunRepository, mock(WorkflowClient.class));
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

  // A plain string with no reference is unchanged.
  @Test
  void passesThroughPlainString() {
    WorkflowRunEntity run = run(str("ref", "just text"));
    parameterManager.resolveParamLayers(run, Optional.empty());
    assertEquals("just text", resolved(run, "ref"));
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

  private static String resolved(WorkflowRunEntity run, String name) {
    return (String)
        run.getParams().stream()
            .filter(p -> name.equals(p.getName()))
            .findFirst()
            .orElseThrow()
            .getValue();
  }
}
