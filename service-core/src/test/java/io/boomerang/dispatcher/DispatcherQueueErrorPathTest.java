package io.boomerang.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.TaskRun;
import io.boomerang.dispatcher.entity.DispatcherEntity;
import io.boomerang.dispatcher.repository.DispatcherRepository;
import io.boomerang.engine.TaskRunService;
import io.boomerang.engine.WorkflowRunStateHelper;
import io.boomerang.workflow.ArtifactService;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The task-queue long poll when a claim step fails: runs already claimed in that cycle are still
 * handed out, a failing query is retried once per re-check interval rather than in a tight loop,
 * and an interrupted poll ends instead of holding its thread for the rest of the window.
 */
class DispatcherQueueErrorPathTest {

  private static final String AGENT = "agent-1";

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
    dispatcher.setTaskTypes(List.of(TaskType.template));
    when(dispatcherRepository.existsById(AGENT)).thenReturn(true);
    when(dispatcherRepository.findTaskTypesByAgentId(AGENT)).thenReturn(dispatcher);
  }

  private static TaskRunEntity taskRun(String id) {
    TaskRunEntity entity = new TaskRunEntity();
    entity.setId(id);
    entity.setType(TaskType.template);
    return entity;
  }

  @Test
  void runsClaimedBeforeAnErrorAreStillHandedOut() {
    when(taskRunService.findClaimable(anyList(), anyInt()))
        .thenReturn(List.of(taskRun("claimed"), taskRun("failing")));
    when(taskRunService.tryClaim("claimed", AGENT)).thenReturn(taskRun("claimed"));
    when(taskRunService.tryClaim("failing", AGENT))
        .thenThrow(new IllegalStateException("claim write failed"));

    ResponseEntity<List<TaskRun>> response = dispatcherService.getTaskQueue(AGENT);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).extracting(TaskRun::getId).containsExactly("claimed");
  }

  @Test
  void aFailingQueryIsRetriedOncePerRecheckAndAnInterruptEndsThePoll() throws Exception {
    when(taskRunService.findClaimable(anyList(), anyInt()))
        .thenThrow(new IllegalStateException("query failed"));

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<ResponseEntity<List<TaskRun>>> poll =
          executor.submit(() -> dispatcherService.getTaskQueue(AGENT));
      Thread.sleep(2500);

      // Three cycles at most in 2.5 s: one at once, then one per 1 s re-check.
      verify(taskRunService, atMost(3)).findClaimable(anyList(), anyInt());

      executor.shutdownNow();
      ResponseEntity<List<TaskRun>> response = poll.get(2, TimeUnit.SECONDS);
      assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    } finally {
      executor.shutdownNow();
    }
  }
}
