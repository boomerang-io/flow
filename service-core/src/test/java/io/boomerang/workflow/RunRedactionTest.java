package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRevisionEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.util.DataAdapterUtil;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workflow.repository.TaskRepository;
import io.boomerang.workflow.repository.TaskRevisionRepository;
import io.boomerang.workflow.repository.WorkflowRevisionRepository;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Sensitive means sensitive UPWARD: the workspace-scoped v2 read redacts password-typed params
 * against BOTH type authorities - the workflow revision's param spec for a workflow-level param,
 * and the catalogue task's own spec for a value typed straight into a task node - while the
 * unscoped read the engine and dispatcher use keeps real values.
 */
class RunRedactionTest extends AbstractEngineIntegrationTest {

  private static final String SECRET = "ghp_secret42";
  private static final String TASK_SECRET = "sk-task-declared-4242";
  private static final String SHORT_SECRET = "abc";
  private static final String QUERY_WORKFLOW = "redaction-query-wf";

  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private WorkflowRevisionRepository workflowRevisionRepository;
  @Autowired private TaskRepository taskRepository;
  @MockitoSpyBean private TaskRevisionRepository spiedTaskRevisionRepository;

  @BeforeEach
  void resetSpy() {
    clearInvocations(spiedTaskRevisionRepository);
  }

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

  /**
   * The catalogue task is the second type authority: a value typed straight into a task node's
   * password-typed param has no workflow-level param to join against, so only the task's own spec
   * (reached through the TaskRun's taskRef/taskVersion) marks it sensitive. The scrub is run-wide -
   * substitution carries the value into a downstream task's result under another name.
   */
  @Test
  void taskDeclaredPasswordParamIsBlankedAndScrubbedRunWide() {
    TaskService.TaskRef catalogueTask = seedTaskWithPasswordParam("redaction-task-declared", "apiKey");

    WorkflowRunEntity run =
        savedWorkflowRun("task-declared-wf", RunStatus.succeeded, RunPhase.completed);

    TaskRunEntity caller =
        savedTaskRun(
            "calls-api",
            TaskType.template,
            RunStatus.succeeded,
            RunPhase.completed,
            run.getWorkflowRef(),
            run.getId());
    caller.setTaskRef(catalogueTask.ref());
    caller.setTaskVersion(catalogueTask.version());
    caller.setParams(new LinkedList<>(List.of(new RunParam("apiKey", TASK_SECRET))));
    caller.getSpec().setScript("curl -H 'X-Api-Key: " + TASK_SECRET + "'");
    taskRunRepository.save(caller);

    TaskRunEntity downstream =
        savedTaskRun(
            "reports",
            TaskType.template,
            RunStatus.succeeded,
            RunPhase.completed,
            run.getWorkflowRef(),
            run.getId());
    downstream.setResults(
        new LinkedList<>(List.of(new RunResult("summary", "called with " + TASK_SECRET))));
    taskRunRepository.save(downstream);

    // The unscoped read the dispatcher claims through keeps the real value.
    WorkflowRun unscoped = workflowRunService.get(run.getId(), true);
    assertEquals(TASK_SECRET, taskByName(unscoped, "calls-api").getParams().get(0).getValue());

    WorkflowRun display = workflowRunService.get(run.getId(), true);
    workflowRunService.filterSensitiveValues(display);
    assertEquals(
        "",
        taskByName(display, "calls-api").getParams().get(0).getValue(),
        "the task's own password-typed param is blanked by name");
    assertFalse(taskByName(display, "calls-api").getSpec().getScript().contains(TASK_SECRET));
    assertEquals(
        "called with " + DataAdapterUtil.REDACTED,
        taskByName(display, "reports").getResults().get(0).getValue(),
        "a task-declared secret is scrubbed run-wide, not only on the task that declares it");
  }

  /**
   * The accepted edge, now reached through the task spec too: a secret shorter than four
   * characters is blanked by name where it is declared, but never value-scrubbed - replacing
   * 1-3 character strings would mangle unrelated text.
   */
  @Test
  void taskDeclaredSecretShorterThanFourCharactersIsBlankedByNameButNotValueScrubbed() {
    TaskService.TaskRef catalogueTask = seedTaskWithPasswordParam("redaction-task-short", "pin");

    WorkflowRunEntity run =
        savedWorkflowRun("short-secret-wf", RunStatus.succeeded, RunPhase.completed);

    TaskRunEntity caller =
        savedTaskRun(
            "uses-pin",
            TaskType.template,
            RunStatus.succeeded,
            RunPhase.completed,
            run.getWorkflowRef(),
            run.getId());
    caller.setTaskRef(catalogueTask.ref());
    caller.setTaskVersion(catalogueTask.version());
    caller.setParams(new LinkedList<>(List.of(new RunParam("pin", SHORT_SECRET))));
    caller.setResults(new LinkedList<>(List.of(new RunResult("echoed", "pin is " + SHORT_SECRET))));
    taskRunRepository.save(caller);

    WorkflowRun display = workflowRunService.get(run.getId(), true);
    workflowRunService.filterSensitiveValues(display);
    assertEquals("", taskByName(display, "uses-pin").getParams().get(0).getValue());
    assertEquals(
        "pin is " + SHORT_SECRET,
        taskByName(display, "uses-pin").getResults().get(0).getValue(),
        "under four characters the value scrub is deliberately not applied");
  }

  /** Two tasks on one response resolve their specs in ONE task-revision query, not one each. */
  @Test
  void taskSpecsForOneResponseAreResolvedInASingleRevisionQuery() {
    TaskService.TaskRef first = seedTaskWithPasswordParam("redaction-task-batch-a", "tokenA");
    TaskService.TaskRef second = seedTaskWithPasswordParam("redaction-task-batch-b", "tokenB");

    WorkflowRunEntity run = savedWorkflowRun("batch-wf", RunStatus.succeeded, RunPhase.completed);
    savedReferencingTaskRun(run, "a", first, "tokenA");
    savedReferencingTaskRun(run, "b", second, "tokenB");

    WorkflowRun display = workflowRunService.get(run.getId(), true);
    clearInvocations(spiedTaskRevisionRepository);
    workflowRunService.filterSensitiveValues(display);

    verify(spiedTaskRevisionRepository, times(1)).findByParentRefInAndVersionIn(any(), any());
    assertEquals("", taskByName(display, "a").getParams().get(0).getValue());
    assertEquals("", taskByName(display, "b").getParams().get(0).getValue());
  }

  /**
   * The paged query attaches no TaskRuns - {@code WorkflowRunService.get} sets them only for
   * {@code withTasks=true} - so the task-spec join must never fire on it: a list page pays for no
   * extra lookup however many runs it returns. Exercised through the unscoped {@code query} plus
   * {@code filterSensitiveValues}, which is exactly what the workspace-scoped overload composes
   * (WorkflowRunService.java:210), without the relationship graph that overload's scoping needs.
   */
  @Test
  void pagedQueryAttachesNoTasksAndIssuesNoTaskRevisionLookup() {
    TaskService.TaskRef catalogueTask = seedTaskWithPasswordParam("redaction-task-query", "apiKey");
    WorkflowRunEntity run =
        savedWorkflowRun(QUERY_WORKFLOW, RunStatus.succeeded, RunPhase.completed);
    savedReferencingTaskRun(run, "calls-api", catalogueTask, "apiKey");

    clearInvocations(spiedTaskRevisionRepository);
    Page<WorkflowRun> page =
        workflowRunService.query(
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(List.of(run.getId())),
            Optional.empty(),
            Optional.empty());
    page.getContent().forEach(workflowRunService::filterSensitiveValues);

    assertEquals(1, page.getContent().size());
    assertNull(page.getContent().get(0).getTasks(), "the paged query carries no tasks");
    verify(spiedTaskRevisionRepository, never()).findByParentRefInAndVersionIn(any(), any());
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  /**
   * A global catalogue task declaring one password-typed param, returned as the (taskRef,
   * taskVersion) pair a TaskRun records for it - taskRef is the TaskEntity id, which is what
   * WorkflowService resolves a workflow's task reference to and DAGUtility stamps on the TaskRun.
   * {@code createGlobal} nulls the id on the way out, so both halves are read back from the store.
   */
  private TaskService.TaskRef seedTaskWithPasswordParam(String name, String paramName) {
    seedRelationshipRoot();
    AbstractParam param = new AbstractParam();
    param.setName(paramName);
    param.setType("password");
    Task task = new Task();
    task.setName(name);
    task.setType(TaskType.template);
    task.getSpec().setImage("busybox:latest");
    task.getSpec().setParams(new LinkedList<>(List.of(param)));
    taskService.createGlobal(task);

    String ref = taskRepository.findByName(name).orElseThrow().getId();
    Integer version =
        spiedTaskRevisionRepository.findByParentRefAndLatestVersion(ref).orElseThrow().getVersion();
    return new TaskService.TaskRef(ref, version);
  }

  private void savedReferencingTaskRun(
      WorkflowRunEntity run, String name, TaskService.TaskRef catalogueTask, String paramName) {
    TaskRunEntity taskRun =
        savedTaskRun(
            name,
            TaskType.template,
            RunStatus.succeeded,
            RunPhase.completed,
            run.getWorkflowRef(),
            run.getId());
    taskRun.setTaskRef(catalogueTask.ref());
    taskRun.setTaskVersion(catalogueTask.version());
    taskRun.setParams(new LinkedList<>(List.of(new RunParam(paramName, TASK_SECRET))));
    taskRunRepository.save(taskRun);
  }

  private static TaskRun taskByName(WorkflowRun run, String name) {
    return run.getTasks().stream()
        .filter(t -> name.equals(t.getName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no TaskRun named " + name));
  }
}
