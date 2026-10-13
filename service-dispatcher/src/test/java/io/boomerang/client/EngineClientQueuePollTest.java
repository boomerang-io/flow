package io.boomerang.client;

import static io.boomerang.client.EngineClient.MIN_IDLE_POLL_SPACING_MS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.model.TaskRun;
import io.boomerang.dispatcher.QueueService;
import io.boomerang.dispatcher.TaskSlots;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Stubber;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * Queue poll pacing: a poll that brought runs, or one the engine held for its window, is followed
 * by the next at once; a poll the engine answered at once with nothing, or that failed, waits so
 * the engine is not asked in a tight loop.
 */
class EngineClientQueuePollTest {

  private final RestTemplate restTemplate = mock(RestTemplate.class);
  private final QueueService queueService = mock(QueueService.class);
  private final EngineClient engineClient = new EngineClient();

  @BeforeEach
  void setUp() {
    engineClient.restTemplate = restTemplate;
    engineClient.queueService = queueService;
    engineClient.taskSlots = new TaskSlots(0);
    ReflectionTestUtils.setField(engineClient, "dispatcherId", "dispatcher-1");
    ReflectionTestUtils.setField(
        engineClient, "startTaskRunURL", "http://engine/api/v1/dispatcher/taskrun/{taskRunId}/start");
    ReflectionTestUtils.setField(
        engineClient,
        "dispatcherQueueTaskURL",
        "http://engine/api/v1/dispatcher/{dispatcherId}/tasks");
  }

  @SuppressWarnings("unchecked")
  private void stubTaskQueue(Stubber answer) {
    answer
        .when(restTemplate)
        .exchange(anyString(), eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class));
  }

  @SuppressWarnings("unchecked")
  private void stubStart(Stubber answer) {
    answer
        .when(restTemplate)
        .exchange(anyString(), eq(HttpMethod.PUT), any(HttpEntity.class), eq(TaskRun.class));
  }

  @SuppressWarnings("unchecked")
  private String polledTaskQueueUrl() {
    ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
    verify(restTemplate)
        .exchange(url.capture(), eq(HttpMethod.GET), isNull(), any(ParameterizedTypeReference.class));
    return url.getValue();
  }

  private static TaskRun taskRun(RunPhase phase, RunStatus status) {
    TaskRun taskRun = new TaskRun();
    taskRun.setId("task-1");
    taskRun.setPhase(phase);
    taskRun.setStatus(status);
    return taskRun;
  }

  private long millisToPoll() {
    long started = System.currentTimeMillis();
    engineClient.retrieveDispatcherTaskQueue();
    return System.currentTimeMillis() - started;
  }

  @Test
  void aPollThatBroughtRunsReturnsAtOnce() {
    TaskRun taskRun = new TaskRun();
    taskRun.setId("task-1");
    stubTaskQueue(doReturn(ResponseEntity.ok(List.of(taskRun))));

    assertThat(millisToPoll()).isLessThan(1000L);
    verify(queueService).processTaskRun(taskRun);
  }

  @Test
  void aPollTheEngineHeldForItsWindowReturnsAtOnce() {
    stubTaskQueue(
        doAnswer(
            invocation -> {
              Thread.sleep(MIN_IDLE_POLL_SPACING_MS);
              return ResponseEntity.noContent().build();
            }));

    assertThat(millisToPoll()).isLessThan(MIN_IDLE_POLL_SPACING_MS + 1000L);
  }

  @Test
  void anEmptyAnswerGivenAtOnceWaitsBeforeTheNextPoll() {
    stubTaskQueue(doReturn(ResponseEntity.noContent().build()));

    assertThat(millisToPoll()).isGreaterThanOrEqualTo(MIN_IDLE_POLL_SPACING_MS - 50L);
  }

  @Test
  void aFailedPollWaitsBeforeTheNextPoll() {
    stubTaskQueue(doThrow(new ResourceAccessException("Connection refused")));

    assertThat(millisToPoll()).isGreaterThanOrEqualTo(MIN_IDLE_POLL_SPACING_MS - 50L);
  }

  @Test
  void aTaskPollAsksForNoMoreThanTheFreeSlots() {
    engineClient.taskSlots = new TaskSlots(5);
    engineClient.taskSlots.take();
    engineClient.taskSlots.take();
    stubTaskQueue(doReturn(ResponseEntity.ok(List.of(taskRun(RunPhase.queued, RunStatus.ready)))));

    engineClient.retrieveDispatcherTaskQueue();

    assertThat(polledTaskQueueUrl()).endsWith("/dispatcher-1/tasks?limit=3");
    // The run handed over to execute took a slot before the next poll could count it.
    assertThat(engineClient.taskSlots.free()).isEqualTo(2);
  }

  @Test
  void aTerminationOrderTakesNoSlot() {
    engineClient.taskSlots = new TaskSlots(5);
    stubTaskQueue(
        doReturn(ResponseEntity.ok(List.of(taskRun(RunPhase.completed, RunStatus.cancelled)))));

    engineClient.retrieveDispatcherTaskQueue();

    assertThat(engineClient.taskSlots.free()).isEqualTo(5);
  }

  @Test
  void anUncappedDispatcherSendsNoLimit() {
    stubTaskQueue(doReturn(ResponseEntity.ok(List.of(taskRun(RunPhase.queued, RunStatus.ready)))));

    engineClient.retrieveDispatcherTaskQueue();

    assertThat(polledTaskQueueUrl()).endsWith("/dispatcher-1/tasks");
  }

  @Test
  void aTaskTheEngineReportsFinishedIsNotStarted() {
    stubStart(doReturn(ResponseEntity.ok(taskRun(RunPhase.completed, RunStatus.cancelled))));

    assertThat(engineClient.startTask("task-1")).isFalse();
  }

  @Test
  void aStartTheEngineRefusesIsNotStarted() {
    stubStart(doThrow(HttpClientErrorException.create(HttpStatus.CONFLICT, "superseded", null, null, null)));

    assertThat(engineClient.startTask("task-1")).isFalse();
  }

  @Test
  void anAcceptedStartGoesAhead() {
    stubStart(doReturn(ResponseEntity.ok(taskRun(RunPhase.queued, RunStatus.ready))));

    assertThat(engineClient.startTask("task-1")).isTrue();
  }

  @Test
  void anUnreachableEngineStillLetsTheTaskGoAhead() {
    stubStart(doThrow(new ResourceAccessException("Connection refused")));

    assertThat(engineClient.startTask("task-1")).isTrue();
  }
}
