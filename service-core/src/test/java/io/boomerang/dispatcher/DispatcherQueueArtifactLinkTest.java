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
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.RunResult;
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
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The task-queue long poll for artifact tasks: a download task whose artifact is missing is ended
 * instead of handed out - succeeded with found=false when the task is optional, failed with the
 * refusal otherwise - while a task whose link was filled leaves with the agent. Each poll claims
 * the missing one first and a second task on the next cycle, so the poll answers with that one.
 */
class DispatcherQueueArtifactLinkTest {

  private static final String AGENT = "agent-1";

  private final DispatcherRepository dispatcherRepository = mock(DispatcherRepository.class);
  private final TaskRunService taskRunService = mock(TaskRunService.class);
  private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
  private final ArtifactService artifactService = mock(ArtifactService.class);

  private final DispatcherService dispatcherService =
      new DispatcherService(
          dispatcherRepository,
          mock(WorkflowRunStateHelper.class),
          taskRunService,
          mongoTemplate,
          artifactService,
          new ClaimFilterService(mock(MongoTemplate.class)),
          mock(io.boomerang.engine.ParameterManager.class),
          mock(io.boomerang.engine.repository.WorkflowRunRepository.class),
          mock(io.boomerang.workflow.TaskService.class),
          new PollWakeups());

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(dispatcherService, "queueEnabled", true);
    DispatcherEntity dispatcher = new DispatcherEntity();
    dispatcher.setId(AGENT);
    dispatcher.setTaskTypes(List.of(TaskType.downloadartifact));
    when(mongoTemplate.findAndModify(
            any(Query.class),
            any(Update.class),
            any(FindAndModifyOptions.class),
            eq(DispatcherEntity.class)))
        .thenReturn(dispatcher);
    when(taskRunService.findClaimable(any(ClaimFilter.class), anyInt()))
        .thenReturn(List.of(download("missing")), List.of(download("present")));
    when(taskRunService.tryClaim(eq("missing"), any(), eq(AGENT))).thenReturn(download("missing"));
    when(taskRunService.tryClaim(eq("present"), any(), eq(AGENT))).thenReturn(download("present"));
    when(artifactService.fillLinkParams(taskRun("present"))).thenReturn(LinkOutcome.FILLED);
  }

  /** Matches the handed-out TaskRun by id; null-safe, as Mockito probes matchers with null. */
  private static TaskRun taskRun(String id) {
    return argThat(task -> task != null && id.equals(task.getId()));
  }

  private static TaskRunEntity download(String id) {
    TaskRunEntity entity = new TaskRunEntity();
    entity.setId(id);
    entity.setType(TaskType.downloadartifact);
    return entity;
  }

  @Test
  void anOptionalDownloadWithNothingToFetchEndsSucceededWithFoundFalse() {
    when(artifactService.fillLinkParams(taskRun("missing"))).thenReturn(LinkOutcome.NOT_FOUND);

    ResponseEntity<List<TaskRun>> response = dispatcherService.getTaskQueue(AGENT, null, null, null, null);

    TaskRunEndRequest request = endOf("missing");
    assertThat(request.getStatus()).isEqualTo(RunStatus.succeeded);
    assertThat(request.getStatusReason()).isEqualTo("ArtifactNotFound");
    assertThat(request.getStatusMessage()).isNotBlank();
    assertThat(request.getResults())
        .extracting(RunResult::getName, RunResult::getValue)
        .containsExactly(Tuple.tuple(ArtifactService.FOUND_RESULT, "false"));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).extracting(TaskRun::getId).containsExactly("present");
    verify(taskRunService, never()).end(eq("present"), any());
  }

  @Test
  void aRefusedDownloadEndsFailedWithTheReason() {
    when(artifactService.fillLinkParams(taskRun("missing")))
        .thenThrow(new BoomerangException(BoomerangError.ARTIFACT_INVALID_REF, "missing"));
    when(artifactService.refusalMessage(any(BoomerangException.class)))
        .thenReturn("No artifact \"missing\" was found on this run.");

    ResponseEntity<List<TaskRun>> response = dispatcherService.getTaskQueue(AGENT, null, null, null, null);

    TaskRunEndRequest request = endOf("missing");
    assertThat(request.getStatus()).isEqualTo(RunStatus.failed);
    assertThat(request.getStatusReason()).isEqualTo("ArtifactRefused");
    assertThat(request.getStatusMessage()).contains("missing");
    assertThat(request.getResults()).isEmpty();
    assertThat(response.getBody()).extracting(TaskRun::getId).containsExactly("present");
  }

  private TaskRunEndRequest endOf(String taskRunId) {
    ArgumentCaptor<Optional<TaskRunEndRequest>> ended = ArgumentCaptor.captor();
    verify(taskRunService).end(eq(taskRunId), ended.capture());
    return ended.getValue().orElseThrow();
  }
}
