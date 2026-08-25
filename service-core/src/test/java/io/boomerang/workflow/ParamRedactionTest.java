package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.util.DataAdapterUtil;
import org.junit.jupiter.api.Test;

/**
 * Redaction must key off the param's own type. The single caller
 * (ParameterService.convertToAbstractParamAndFilter) passes "password", so redacting regardless of
 * type blanks the value of every global parameter the API returns, not just the secrets.
 */
class ParamRedactionTest {

  private static AbstractParam param(String type, Object value) {
    AbstractParam param = new AbstractParam();
    param.setType(type);
    param.setValue(value);
    param.setDefaultValue(value);
    return param;
  }

  @Test
  void aPasswordParamIsRedacted() {
    AbstractParam result = DataAdapterUtil.filterAbstractParam(param("password", "s3cret"), false, "password");

    assertNull(result.getValue());
    assertTrue(result.getHiddenValue());
  }

  @Test
  void aNonPasswordParamKeepsItsValue() {
    AbstractParam result = DataAdapterUtil.filterAbstractParam(param("text", "not-a-secret"), false, "password");

    assertEquals("not-a-secret", result.getValue(), "a text param must not be redacted");
    assertNull(result.getHiddenValue(), "a text param must not be marked hidden");
  }

  @Test
  void theDefaultValueVariantRedactsOnlyTheDefault() {
    AbstractParam result = DataAdapterUtil.filterAbstractParam(param("password", "s3cret"), true, "password");

    assertNull(result.getDefaultValue());
    assertEquals("s3cret", result.getValue(), "only the default value is cleared in this mode");
  }

  @Test
  void aNullParamIsReturnedRatherThanThrowing() {
    assertNull(DataAdapterUtil.filterAbstractParam(null, false, "password"));
  }

  // ── Run-payload redaction (the upward surface) ──────────────────────────────

  private static AbstractParam spec(String name, String type) {
    AbstractParam param = new AbstractParam();
    param.setName(name);
    param.setType(type);
    return param;
  }

  @Test
  void sensitiveValuesJoinsPasswordSpecTypeToResolvedRunValuesByName() {
    var specs = java.util.List.of(spec("githubToken", "password"), spec("plain", "text"));
    var runParams =
        java.util.List.of(
            new RunParam("githubToken", "ghp_secret42"), new RunParam("plain", "hello"));

    var secrets = DataAdapterUtil.sensitiveValues(specs, runParams, "password");

    assertEquals(java.util.Set.of("ghp_secret42"), secrets);
  }

  @Test
  void redactWorkflowRunBlanksByNameAndScrubsTasksByValue() {
    var specs = java.util.List.of(spec("githubToken", "password"), spec("plain", "text"));
    WorkflowRun run = new WorkflowRun();
    run.setParams(
        java.util.List.of(
            new RunParam("githubToken", "ghp_secret42"), new RunParam("plain", "hello")));
    run.setResults(java.util.List.of(new RunResult("echoed", "value is ghp_secret42 here")));
    TaskRun task = new TaskRun();
    // The secret appears under a DIFFERENT name after substitution - name-joins cannot catch it.
    task.setParams(java.util.List.of(new RunParam("token", "ghp_secret42")));
    task.getSpec().setScript("#!/bin/sh\ncurl -H 'Authorization: ghp_secret42'");
    task.getSpec().setArguments(java.util.List.of("--token", "ghp_secret42"));
    task.setResults(java.util.List.of(new RunResult("out", "ok")));
    run.setTasks(java.util.List.of(task));

    DataAdapterUtil.redactWorkflowRun(run, specs, "password");

    assertEquals(DataAdapterUtil.REDACTED, run.getParams().get(0).getValue());
    assertEquals("hello", run.getParams().get(1).getValue(), "non-password params stay");
    assertEquals("value is " + DataAdapterUtil.REDACTED + " here", run.getResults().get(0).getValue());
    assertEquals(DataAdapterUtil.REDACTED, task.getParams().get(0).getValue());
    assertTrue(task.getSpec().getScript().contains(DataAdapterUtil.REDACTED));
    assertEquals(java.util.List.of("--token", DataAdapterUtil.REDACTED), task.getSpec().getArguments());
    assertEquals("ok", task.getResults().get(0).getValue(), "untainted results stay");
  }

  @Test
  void shortSecretsAreBlankedByNameButNotValueScrubbed() {
    var specs = java.util.List.of(spec("pin", "password"));
    WorkflowRun run = new WorkflowRun();
    run.setParams(java.util.List.of(new RunParam("pin", "abc")));
    TaskRun task = new TaskRun();
    task.getSpec().setScript("abcdef abc");
    run.setTasks(java.util.List.of(task));

    DataAdapterUtil.redactWorkflowRun(run, specs, "password");

    assertEquals(DataAdapterUtil.REDACTED, run.getParams().get(0).getValue());
    assertEquals(
        "abcdef abc",
        task.getSpec().getScript(),
        "a sub-4-character secret must not shred unrelated text");
  }

  @Test
  void aStructuredValueCarryingASecretIsReplacedWholesale() {
    TaskRun task = new TaskRun();
    task.setParams(
        java.util.List.of(new RunParam("config", java.util.Map.of("auth", "ghp_secret42"))));

    DataAdapterUtil.redactTaskRun(task, java.util.Set.of("ghp_secret42"));

    assertEquals(DataAdapterUtil.REDACTED, task.getParams().get(0).getValue());
  }
}
