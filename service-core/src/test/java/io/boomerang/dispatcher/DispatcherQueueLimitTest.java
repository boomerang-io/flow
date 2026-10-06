package io.boomerang.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.TaskRun;
import io.boomerang.dispatcher.entity.DispatcherEntity;
import io.boomerang.dispatcher.repository.DispatcherRepository;
import io.boomerang.engine.TaskRunService;
import io.boomerang.engine.WorkflowRunStateHelper;
import io.boomerang.workflow.ArtifactService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The task poll's {@code limit}: a dispatcher asks for no more new TaskRuns than it has free
 * slots, never more than a page, and a limit of 0 still hands out termination orders.
 */
class DispatcherQueueLimitTest {

  private static final String AGENT = "agent-1";
  private static final List<TaskType> TYPES = List.of(TaskType.template);

  private final DispatcherRepository dispatcherRepository = mock(DispatcherRepository.class);
  private final TaskRunService taskRunService = mock(TaskRunService.class);

  private final DispatcherService dispatcherService =
      new DispatcherService(
          dispatcherRepository,
          mock(WorkflowRunStateHelper.class),
          taskRunService,
          mock(MongoTemplate.class),
          mock(ArtifactService.class));

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(dispatcherService, "queueEnabled", true);
    DispatcherEntity dispatcher = new DispatcherEntity();
    dispatcher.setId(AGENT);
    dispatcher.setTaskTypes(TYPES);
    when(dispatcherRepository.existsById(AGENT)).thenReturn(true);
    when(dispatcherRepository.findTaskTypesByAgentId(AGENT)).thenReturn(dispatcher);
    // One claimable run, so a poll answers at once instead of holding for its window.
    when(taskRunService.findClaimable(anyList(), anyInt())).thenReturn(List.of(taskRun("ready")));
    when(taskRunService.tryClaim("ready", AGENT)).thenReturn(taskRun("ready"));
  }

  private static TaskRunEntity taskRun(String id) {
    TaskRunEntity entity = new TaskRunEntity();
    entity.setId(id);
    entity.setType(TaskType.template);
    return entity;
  }

  @Test
  void aLimitSizesTheExecutionPage() {
    dispatcherService.getTaskQueue(AGENT, 3);

    verify(taskRunService).findClaimable(eq(TYPES), eq(3));
  }

  @Test
  void noLimitClaimsAFullPage() {
    dispatcherService.getTaskQueue(AGENT);

    verify(taskRunService).findClaimable(eq(TYPES), eq(20));
  }

  @Test
  void aLimitAboveAPageIsHeldToAPage() {
    dispatcherService.getTaskQueue(AGENT, 50);

    verify(taskRunService).findClaimable(eq(TYPES), eq(20));
  }

  @Test
  void aLimitOfZeroClaimsNothingButStillHandsOutTerminationOrders() {
    TaskRunEntity cancelled = taskRun("cancelled");
    cancelled.setPhase(RunPhase.completed);
    cancelled.setStatus(RunStatus.cancelled);
    when(taskRunService.findClaimableForTermination(anyList(), anyInt()))
        .thenReturn(List.of(cancelled));
    when(taskRunService.tryClaimForTermination("cancelled", AGENT)).thenReturn(cancelled);

    ResponseEntity<List<TaskRun>> response = dispatcherService.getTaskQueue(AGENT, 0);

    verify(taskRunService, never()).findClaimable(anyList(), anyInt());
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).extracting(TaskRun::getId).containsExactly("cancelled");
  }
}
