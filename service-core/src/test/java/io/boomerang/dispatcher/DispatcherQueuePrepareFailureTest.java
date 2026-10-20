package io.boomerang.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.dispatcher.entity.DispatcherEntity;
import io.boomerang.dispatcher.repository.DispatcherRepository;
import io.boomerang.engine.TaskRunService;
import io.boomerang.engine.WorkflowRunStateHelper;
import io.boomerang.engine.model.ClaimFilter;
import io.boomerang.workflow.ArtifactService;
import io.boomerang.workflow.ArtifactService.LinkOutcome;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * A task the poll claimed but could not prepare for hand-out is ended failed with {@code
 * DispatchPrepareFailed} instead of staying claimed by a dispatcher that never hears of it, and
 * the other task claimed in the same cycle is still handed out.
 */
class DispatcherQueuePrepareFailureTest {

  private static final String AGENT = "agent-1";

  private final DispatcherRepository dispatcherRepository = mock(DispatcherRepository.class);
  private final TaskRunService taskRunService = mock(TaskRunService.class);
  private final ArtifactService artifactService = mock(ArtifactService.class);

  private final DispatcherService dispatcherService =
      new DispatcherService(
          dispatcherRepository,
          mock(WorkflowRunStateHelper.class),
          taskRunService,
          mock(MongoTemplate.class),
          artifactService,
          new ClaimFilterService(mock(MongoTemplate.class)),
          mock(io.boomerang.engine.ParameterManager.class),
          mock(io.boomerang.engine.repository.WorkflowRunRepository.class),
          mock(io.boomerang.workflow.TaskService.class));

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(dispatcherService, "queueEnabled", true);
    DispatcherEntity dispatcher = new DispatcherEntity();
    dispatcher.setId(AGENT);
    dispatcher.setTaskTypes(List.of(TaskType.uploadartifact));
    when(dispatcherRepository.existsById(AGENT)).thenReturn(true);
    when(dispatcherRepository.findTaskTypesByAgentId(AGENT)).thenReturn(dispatcher);
    when(taskRunService.findClaimable(any(ClaimFilter.class), anyInt()))
        .thenReturn(List.of(upload("broken"), upload("fine")), List.of());
    when(taskRunService.tryClaim("broken", AGENT)).thenReturn(upload("broken"));
    when(taskRunService.tryClaim("fine", AGENT)).thenReturn(upload("fine"));
    when(artifactService.fillLinkParams(taskRun("broken")))
        .thenThrow(new NullPointerException("identity is null"));
    when(artifactService.fillLinkParams(taskRun("fine"))).thenReturn(LinkOutcome.FILLED);
  }

  /** Matches the handed-out TaskRun by id; null-safe, as Mockito probes matchers with null. */
  private static TaskRun taskRun(String id) {
    return argThat(task -> task != null && id.equals(task.getId()));
  }

  private static TaskRunEntity upload(String id) {
    TaskRunEntity entity = new TaskRunEntity();
    entity.setId(id);
    entity.setType(TaskType.uploadartifact);
    return entity;
  }

  @Test
  void aTaskThatCannotBePreparedEndsFailedAndTheRestOfTheCycleIsHandedOut() {
    ResponseEntity<List<TaskRun>> response = dispatcherService.getTaskQueue(AGENT, null, null, null, null);

    ArgumentCaptor<Optional<TaskRunEndRequest>> ended = ArgumentCaptor.captor();
    verify(taskRunService).end(eq("broken"), ended.capture());
    TaskRunEndRequest request = ended.getValue().orElseThrow();
    assertThat(request.getStatus()).isEqualTo(RunStatus.failed);
    assertThat(request.getStatusReason()).isEqualTo("DispatchPrepareFailed");
    assertThat(request.getStatusMessage()).contains("identity is null");
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).extracting(TaskRun::getId).containsExactly("fine");
    verify(taskRunService, never()).end(eq("fine"), any());
  }
}
