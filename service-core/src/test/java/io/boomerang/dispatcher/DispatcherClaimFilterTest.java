package io.boomerang.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.boomerang.common.entity.TaskEntity;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.enums.WorkflowStatus;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.DispatcherRegistrationRequest;
import io.boomerang.common.model.RunClaim;
import io.boomerang.common.model.TaskRun;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workflow.repository.TaskRepository;
import io.boomerang.workflow.repository.WorkflowRepository;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * A task poll narrowed by task slug or by a label on the workflow definition claims only the
 * matching work, hands out termination orders for only that work, and claims nothing - at once -
 * when its filter matches nothing.
 */
class DispatcherClaimFilterTest extends AbstractEngineIntegrationTest {

  @Autowired private DispatcherService dispatcherService;
  @Autowired private TaskRepository taskRepository;
  @Autowired private WorkflowRepository workflowRepository;

  private final String tag = UUID.randomUUID().toString().substring(0, 8);
  private final List<String> taskIds = new ArrayList<>();
  private final List<String> workflowIds = new ArrayList<>();
  private final List<String> taskRunIds = new ArrayList<>();

  @AfterEach
  void cleanUp() {
    taskRunIds.forEach(taskRunRepository::deleteById);
    taskIds.forEach(taskRepository::deleteById);
    workflowIds.forEach(workflowRepository::deleteById);
  }

  private String dispatcher() {
    return dispatcherService.register(
        new DispatcherRegistrationRequest(
            "filter-" + tag, "filter-" + tag + ".local", List.of("template")));
  }

  private String task(String slug) {
    TaskEntity task = new TaskEntity();
    task.setName(slug);
    task.setType(TaskType.template);
    String id = taskRepository.save(task).getId();
    taskIds.add(id);
    return id;
  }

  private String workflow(String workload) {
    WorkflowEntity workflow = new WorkflowEntity();
    workflow.setName("filter-" + tag + "-" + workload);
    workflow.setStatus(WorkflowStatus.active);
    workflow.getLabels().put("archie.io/workload", workload);
    String id = workflowRepository.save(workflow).getId();
    workflowIds.add(id);
    return id;
  }

  private String run(String taskRef, String workflowRef, RunStatus status, RunPhase phase) {
    TaskRunEntity run =
        savedTaskRun(
            "filter-" + UUID.randomUUID(),
            TaskType.template,
            status,
            phase,
            workflowRef,
            "filter-run-" + tag);
    run.setTaskRef(taskRef);
    taskRunRepository.save(run);
    taskRunIds.add(run.getId());
    return run.getId();
  }

  // A cancelled run a dispatcher still owns: what a termination order is made of.
  private String ownedCancelledRun(String taskRef, String owner) {
    String id = run(taskRef, null, RunStatus.cancelled, RunPhase.completed);
    TaskRunEntity run = taskRunRepository.findById(id).orElseThrow();
    RunClaim claim = new RunClaim();
    claim.setBy(owner);
    claim.setAt(new Date());
    claim.setSeq(1L);
    run.setClaim(claim);
    taskRunRepository.save(run);
    return id;
  }

  private static List<String> ids(ResponseEntity<List<TaskRun>> response) {
    return (response.getBody() == null)
        ? List.of()
        : response.getBody().stream().map(TaskRun::getId).toList();
  }

  private boolean claimed(String taskRunId) {
    TaskRunEntity run = taskRunRepository.findById(taskRunId).orElseThrow();
    return run.getClaim() != null && run.getClaim().getBy() != null;
  }

  @Test
  void aTaskFilterClaimsOnlyThatTasksRuns() {
    String agent = run(task("filter-" + tag + "-agent"), null, RunStatus.ready, RunPhase.pending);
    String other = run(task("filter-" + tag + "-other"), null, RunStatus.ready, RunPhase.pending);

    List<String> delivered =
        ids(
            dispatcherService.getTaskQueue(
                dispatcher(), null, null, "filter-" + tag + "-ag*", null));

    assertThat(delivered).containsExactly(agent);
    assertThat(claimed(other)).isFalse();
  }

  @Test
  void aWorkflowLabelFilterClaimsOnlyRunsOfLabelledWorkflows() {
    String taskRef = task("filter-" + tag + "-shared");
    String knowledge =
        run(taskRef, workflow("knowledge-" + tag), RunStatus.ready, RunPhase.pending);
    String analysis = run(taskRef, workflow("analysis-" + tag), RunStatus.ready, RunPhase.pending);

    List<String> delivered =
        ids(
            dispatcherService.getTaskQueue(
                dispatcher(), null, null, null, "archie.io/workload=knowledge-" + tag));

    assertThat(delivered).containsExactly(knowledge);
    assertThat(claimed(analysis)).isFalse();
  }

  @Test
  void aFilterThatMatchesNothingClaimsNothingAndAnswersAtOnce() {
    String ready = run(task("filter-" + tag + "-ready"), null, RunStatus.ready, RunPhase.pending);

    long started = System.currentTimeMillis();
    ResponseEntity<List<TaskRun>> response =
        dispatcherService.getTaskQueue(dispatcher(), null, null, "no-such-task-" + tag, null);

    assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    assertThat(System.currentTimeMillis() - started).isLessThan(5000L);
    assertThat(claimed(ready)).isFalse();
  }

  @Test
  void terminationOrdersFollowTheFilter() {
    String owner = dispatcher();
    String mine = ownedCancelledRun(task("filter-" + tag + "-mine"), owner);
    String theirs = ownedCancelledRun(task("filter-" + tag + "-theirs"), owner);

    List<String> delivered =
        ids(dispatcherService.getTaskQueue(owner, 0, null, "filter-" + tag + "-mine", null));

    assertThat(delivered).containsExactly(mine);
    assertThat(claimed(theirs)).isTrue();
  }

  @Test
  void aTypeTheDispatcherDidNotRegisterIsRejected() {
    BoomerangException rejected =
        assertThrows(
            BoomerangException.class,
            () -> dispatcherService.getTaskQueue(dispatcher(), null, "script", null, null));

    assertEquals(BoomerangError.QUERY_INVALID_FILTERS.getCode(), rejected.getCode());
  }
}
