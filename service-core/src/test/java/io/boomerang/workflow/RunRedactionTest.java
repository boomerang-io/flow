package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRevisionEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.util.DataAdapterUtil;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workflow.repository.WorkflowRevisionRepository;
import java.util.LinkedList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Closes the run-payload redaction gap (task-contract-research.md §7): sensitive means sensitive
 * UPWARD, so the workspace-scoped v2 read redacts password-typed params by joining the run against
 * its workflow revision's param spec, while the unscoped read the engine and dispatcher use keeps
 * real values.
 */
class RunRedactionTest extends AbstractEngineIntegrationTest {

  private static final String SECRET = "ghp_secret42";

  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private WorkflowRevisionRepository workflowRevisionRepository;

  @Test
  void redactForDisplayBlanksPasswordParamsAndScrubsTasksButUnscopedReadDoesNot() {
    AbstractParam passwordSpec = new AbstractParam();
    passwordSpec.setName("githubToken");
    passwordSpec.setType("password");
    WorkflowRevisionEntity revision = new WorkflowRevisionEntity();
    revision.setParams(List.of(passwordSpec));
    revision = workflowRevisionRepository.save(revision);

    WorkflowRunEntity run =
        savedWorkflowRun("redaction-wf", RunStatus.succeeded, RunPhase.completed);
    run.setWorkflowRevisionRef(revision.getId());
    run.setParams(new LinkedList<>(List.of(new RunParam("githubToken", SECRET))));
    workflowRunRepository.save(run);

    TaskRunEntity task =
        savedTaskRun(
            "uses-token",
            TaskType.template,
            RunStatus.succeeded,
            RunPhase.completed,
            run.getWorkflowRef(),
            run.getId());
    // Post-substitution the secret sits under a different name and inside the script.
    task.setParams(new LinkedList<>(List.of(new RunParam("token", SECRET))));
    task.getSpec().setScript("#!/bin/sh\ncurl -H 'Authorization: " + SECRET + "'");
    taskRunRepository.save(task);

    // The unscoped read (engine/dispatcher path) must keep real values.
    WorkflowRun unscoped = workflowRunService.get(run.getId(), true);
    assertEquals(SECRET, unscoped.getParams().get(0).getValue());
    assertEquals(SECRET, unscoped.getTasks().get(0).getParams().get(0).getValue());

    // The display path redacts by name at the workflow level and by value below it.
    WorkflowRun display = workflowRunService.get(run.getId(), true);
    workflowRunService.filterSensitiveValues(display);
    assertEquals("", display.getParams().get(0).getValue(), "name-join blanks to empty, matching filterRunParamValueByFieldType");
    assertEquals(DataAdapterUtil.REDACTED, display.getTasks().get(0).getParams().get(0).getValue());
    assertFalse(display.getTasks().get(0).getSpec().getScript().contains(SECRET));
    assertTrue(
        display.getTasks().get(0).getSpec().getScript().contains(DataAdapterUtil.REDACTED));
  }
}
