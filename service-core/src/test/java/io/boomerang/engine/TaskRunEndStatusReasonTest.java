package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunEndRequest;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * {@code statusReason} is a closed-set typed cause paired with the human-readable {@code
 * statusMessage} (e.g. OOMKilled, ImagePull) - an executor reports it on {@link
 * io.boomerang.common.model.TaskRunEndRequest}, and {@link TaskRunService#end} must persist and
 * echo it back exactly as it does {@code statusMessage}.
 */
class TaskRunEndStatusReasonTest extends AbstractEngineIntegrationTest {

  @Test
  void statusReasonFromTheEndRequestIsPersistedAndSerialised() {
    WorkflowRunEntity wfRun =
        savedWorkflowRun("status-reason-wf", RunStatus.running, RunPhase.running);
    TaskRunEntity task =
        savedTaskRun(
            "oom-killed",
            TaskType.template,
            RunStatus.running,
            RunPhase.running,
            wfRun.getWorkflowRef(),
            wfRun.getId());

    TaskRunEndRequest endRequest = new TaskRunEndRequest();
    endRequest.setStatus(RunStatus.failed);
    endRequest.setStatusMessage("The container was killed for exceeding its memory limit.");
    endRequest.setStatusReason("OOMKilled");

    ResponseEntity<TaskRun> response = taskRunService.end(task.getId(), Optional.of(endRequest));

    assertEquals(
        "OOMKilled",
        response.getBody().getStatusReason(),
        "the returned TaskRun must echo the typed cause immediately");

    TaskRunEntity persisted = taskRunRepository.findById(task.getId()).orElseThrow();
    assertEquals("OOMKilled", persisted.getStatusReason());
    assertEquals(
        "The container was killed for exceeding its memory limit.", persisted.getStatusMessage());
  }

  @Test
  void aBlankStatusReasonLeavesTheFieldUnset() {
    WorkflowRunEntity wfRun =
        savedWorkflowRun("status-reason-blank-wf", RunStatus.running, RunPhase.running);
    TaskRunEntity task =
        savedTaskRun(
            "no-reason",
            TaskType.template,
            RunStatus.running,
            RunPhase.running,
            wfRun.getWorkflowRef(),
            wfRun.getId());

    TaskRunEndRequest endRequest = new TaskRunEndRequest();
    endRequest.setStatus(RunStatus.succeeded);

    taskRunService.end(task.getId(), Optional.of(endRequest));

    TaskRunEntity persisted = taskRunRepository.findById(task.getId()).orElseThrow();
    assertNull(persisted.getStatusReason());
  }
}
