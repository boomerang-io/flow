package io.boomerang.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.AgentRegistrationRequest;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.dispatcher.DispatcherService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.ResponseEntity;

/**
 * Claim-seq fencing at the task start/end handlers. Every claim increments {@code claim.seq},
 * so a dispatch still carrying a superseded claim identifies itself and is rejected; the current
 * claimant proceeds. A request with no claimant identity (the v1 agent protocol) is accepted as
 * legacy.
 */
class TaskClaimFencingTest extends AbstractEngineIntegrationTest {

  @Autowired private DispatcherService dispatcherService;
  @Autowired private TaskExecutionService taskExecutionService;
  @Autowired private TaskRunService taskRunService;
  @Autowired private MongoTemplate mongoTemplate;

  private String registerAgent(String name) {
    return dispatcherService.register(
        new AgentRegistrationRequest(name, name + ".local", List.of("template")));
  }

  private static boolean containsId(ResponseEntity<List<TaskRun>> response, String id) {
    return response != null
        && response.getBody() != null
        && response.getBody().stream().anyMatch(t -> id.equals(t.getId()));
  }

  @Test
  void staleClaimSeqDispatchIsRejected() {
    WorkflowRunEntity wfRun = savedWorkflowRun("fencing-wf", RunStatus.running, RunPhase.running);
    String taskRunId =
        savedTaskRun(
                "fenced-task",
                TaskType.template,
                RunStatus.ready,
                RunPhase.pending,
                wfRun.getWorkflowRef(),
                wfRun.getId())
            .getId();
    String agentA = registerAgent("fencing-agent-a");
    String agentB = registerAgent("fencing-agent-b");

    // First claim: agent A owns seq 1.
    assertTrue(containsId(dispatcherService.getTaskQueue(agentA), taskRunId));
    assertEquals(1L, taskRunRepository.findById(taskRunId).orElseThrow().getClaim().getSeq());

    // A requeue clears claim.by/claim.at/claim.leaseExpiresAt but never claim.seq; agent B's
    // claim then owns seq 2.
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("_id").is(taskRunId)),
        new Update()
            .unset("claim.by")
            .unset("claim.at")
            .unset("claim.leaseExpiresAt")
            .set("status", RunStatus.ready)
            .set("phase", RunPhase.pending),
        TaskRunEntity.class);
    assertTrue(containsId(dispatcherService.getTaskQueue(agentB), taskRunId));
    TaskRunEntity reclaimed = taskRunRepository.findById(taskRunId).orElseThrow();
    assertEquals(agentB, reclaimed.getClaim().getBy());
    assertEquals(2L, reclaimed.getClaim().getSeq());

    // Agent A's stale start (seq 1) is rejected: the TaskRun never enters running.
    taskExecutionService.start(taskRunId, Optional.of(agentA), Optional.of(1L));
    TaskRunEntity afterStaleStart = taskRunRepository.findById(taskRunId).orElseThrow();
    assertEquals(RunPhase.queued, afterStaleStart.getPhase());
    assertEquals(RunStatus.ready, afterStaleStart.getStatus());
    assertEquals(agentB, afterStaleStart.getClaim().getBy());

    // The current claimant's start proceeds.
    taskExecutionService.start(taskRunId, Optional.of(agentB), Optional.of(2L));
    awaitEngine("current claimant's start to run the task")
        .untilAsserted(
            () -> {
              TaskRunEntity running = taskRunRepository.findById(taskRunId).orElseThrow();
              assertEquals(RunStatus.running, running.getStatus());
              assertEquals(RunPhase.running, running.getPhase());
            });

    // Agent A's stale end (seq 1) is rejected: the TaskRun stays running under agent B.
    taskExecutionService.end(taskRunId, Optional.of(agentA), Optional.of(1L));
    TaskRunEntity afterStaleEnd = taskRunRepository.findById(taskRunId).orElseThrow();
    assertEquals(RunPhase.running, afterStaleEnd.getPhase());
    assertEquals(RunStatus.running, afterStaleEnd.getStatus());

    // The v1 protocol end carries no identity and is accepted as legacy.
    TaskRunEndRequest endRequest = new TaskRunEndRequest();
    endRequest.setStatus(RunStatus.succeeded);
    taskRunService.end(taskRunId, Optional.of(endRequest));
    awaitEngine("legacy-protocol end to complete the task")
        .untilAsserted(
            () -> {
              TaskRunEntity completed = taskRunRepository.findById(taskRunId).orElseThrow();
              assertEquals(RunStatus.succeeded, completed.getStatus());
              assertEquals(RunPhase.completed, completed.getPhase());
            });
  }
}
