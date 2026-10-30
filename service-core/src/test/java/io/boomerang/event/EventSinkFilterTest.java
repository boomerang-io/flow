package io.boomerang.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.TaskRunTransition;
import io.boomerang.common.model.WorkflowRunTransition;
import io.boomerang.core.config.RestConfig;
import io.boomerang.event.config.EventSinkProperties;
import io.boomerang.event.config.EventSinkProperties.Destination;
import io.boomerang.event.entity.EventOutboxEntity;
import io.boomerang.event.enums.EventPayload;
import io.boomerang.event.repository.EventOutboxRepository;
import io.boomerang.workflow.TaskService;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

/**
 * Sink filters: a transition no sink wants writes no outbox row, a row is posted only to the
 * destinations whose own filter (labels included) admits it, and a bad filter value fails at bind.
 */
class EventSinkFilterTest {

  private static final String ALL_URL = "http://all.test/events";
  private static final String LEGAL_URL = "http://legal.test/events";

  @Test
  void typesKeepTaskRunTransitionsOut() {
    EventOutboxRepository repository = mock(EventOutboxRepository.class);
    CloudEventsBridge bridge = bridge(repository, props(List.of("workflowrun"), List.of(), List.of()));

    bridge.onTaskRunTransition(taskRun(RunStatus.ready, RunStatus.running, RunPhase.running));
    verify(repository, never()).insert(any(EventOutboxEntity.class));

    bridge.onWorkflowRunTransition(workflowRun(RunStatus.ready, RunStatus.running, RunPhase.running));
    verify(repository).insert(any(EventOutboxEntity.class));
  }

  @Test
  void terminalCoversTheFiveEndStatuses() {
    EventSinkProperties properties = props(List.of(), List.of("terminal"), List.of());

    for (RunStatus status : RunStatus.values()) {
      boolean terminal =
          List.of(
                  RunStatus.succeeded,
                  RunStatus.failed,
                  RunStatus.cancelled,
                  RunStatus.timedout,
                  RunStatus.invalid)
              .contains(status);
      assertThat(properties.admits("workflowrun", status, RunPhase.completed))
          .as(status.name())
          .isEqualTo(terminal);
    }

    EventOutboxRepository repository = mock(EventOutboxRepository.class);
    CloudEventsBridge bridge = bridge(repository, properties);
    bridge.onWorkflowRunTransition(workflowRun(RunStatus.ready, RunStatus.running, RunPhase.running));
    verify(repository, never()).insert(any(EventOutboxEntity.class));
    bridge.onWorkflowRunTransition(workflowRun(RunStatus.running, RunStatus.failed, RunPhase.completed));
    verify(repository).insert(any(EventOutboxEntity.class));
  }

  @Test
  void phasesCompletedIsTheTrueEndOfARunWithFinallyTasks() {
    EventOutboxRepository repository = mock(EventOutboxRepository.class);
    CloudEventsBridge bridge = bridge(repository, props(List.of(), List.of(), List.of("completed")));

    // failed while the finally stage still runs
    bridge.onWorkflowRunTransition(workflowRun(RunStatus.running, RunStatus.failed, RunPhase.running));
    verify(repository, never()).insert(any(EventOutboxEntity.class));

    bridge.onWorkflowRunTransition(workflowRun(RunStatus.failed, RunStatus.failed, RunPhase.completed));
    verify(repository).insert(any(EventOutboxEntity.class));
  }

  @Test
  void aLabelFilteredDestinationIsPostedOnlyForARunCarryingTheLabel() throws Exception {
    RestTemplate restTemplate = new RestConfig().internalRestTemplate();
    MockRestServiceServer server =
        MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
    EventSinkProperties properties =
        new EventSinkProperties(
            true,
            EventPayload.thin,
            List.of(ALL_URL),
            List.of(
                new Destination(
                    LEGAL_URL, null, null, List.of(), List.of(), List.of(), List.of("team=legal"))),
            List.of(),
            List.of(),
            List.of());
    EventSinkService service = new EventSinkService(properties, restTemplate, mock(TaskService.class));

    server.expect(requestTo(ALL_URL)).andRespond(withSuccess());
    server.expect(requestTo(LEGAL_URL)).andRespond(withSuccess());
    service.deliverStatusCloudEvent(run(Map.of("team", "legal")), row());
    server.verify();

    // The unlabelled run reaches the unfiltered destination only: a second LEGAL_URL post would
    // fail the mock server.
    server.reset();
    server.expect(requestTo(ALL_URL)).andRespond(withSuccess());
    service.deliverStatusCloudEvent(run(Map.of("team", "sales")), row());
    server.verify();
  }

  @Test
  void aRowNoSinkAdmitsPostsNothing() throws Exception {
    RestTemplate restTemplate = new RestConfig().internalRestTemplate();
    MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    EventSinkProperties properties =
        new EventSinkProperties(
            true,
            EventPayload.thin,
            List.of(),
            List.of(
                new Destination(
                    LEGAL_URL, null, null, List.of(), List.of(), List.of(), List.of("team=legal"))),
            List.of(),
            List.of(),
            List.of());

    new EventSinkService(properties, restTemplate, mock(TaskService.class))
        .deliverStatusCloudEvent(run(Map.of()), row());

    server.verify();
  }

  @Test
  void aDestinationWithoutAFilterInheritsTheTopLevelOne() {
    EventSinkProperties properties =
        new EventSinkProperties(
            true,
            EventPayload.thin,
            List.of(ALL_URL),
            List.of(
                new Destination(
                    LEGAL_URL, null, null, List.of(), List.of("failed"), List.of(), List.of())),
            List.of(),
            List.of("terminal"),
            List.of());

    assertThat(properties.admits("workflowrun", RunStatus.succeeded, RunPhase.completed)).isTrue();
    assertThat(properties.admits("workflowrun", RunStatus.running, RunPhase.running)).isFalse();
    Destination own = properties.sinks().get(1);
    assertThat(own.admits("workflowrun", RunStatus.succeeded, RunPhase.completed, Map.of()))
        .isFalse();
    assertThat(own.admits("workflowrun", RunStatus.failed, RunPhase.completed, Map.of())).isTrue();
  }

  @Test
  void bindingRejectsAnUnknownStatusNamingTheKey() {
    Binder binder =
        new Binder(
            new MapConfigurationPropertySource(
                Map.of("flow.events.sink.statuses", "terminal,exploded")));

    assertThatThrownBy(() -> binder.bind("flow.events.sink", EventSinkProperties.class).get())
        .hasStackTraceContaining("flow.events.sink.statuses: unknown status 'exploded'");
  }

  @Test
  void bindingReadsDestinationFiltersAndRejectsABadLabel() {
    Binder binder =
        new Binder(
            new MapConfigurationPropertySource(
                Map.of(
                    "flow.events.sink.destinations[0].url", LEGAL_URL,
                    "flow.events.sink.destinations[0].labels", "team=legal",
                    "flow.events.sink.destinations[0].types", "taskrun")));
    Destination bound =
        binder.bind("flow.events.sink", EventSinkProperties.class).get().destinations().get(0);
    assertThat(bound.labels()).containsExactly("team=legal");
    assertThat(bound.types()).containsExactly("taskrun");

    Binder bad =
        new Binder(
            new MapConfigurationPropertySource(
                Map.of(
                    "flow.events.sink.destinations[0].url", LEGAL_URL,
                    "flow.events.sink.destinations[0].labels", "legal")));
    assertThatThrownBy(() -> bad.bind("flow.events.sink", EventSinkProperties.class).get())
        .hasStackTraceContaining("labels: 'legal' is not key=value");
  }

  private static EventSinkProperties props(
      List<String> types, List<String> statuses, List<String> phases) {
    return new EventSinkProperties(
        true, EventPayload.thin, List.of(ALL_URL), List.of(), types, statuses, phases);
  }

  private static CloudEventsBridge bridge(
      EventOutboxRepository repository, EventSinkProperties properties) {
    return new CloudEventsBridge(repository, properties);
  }

  private static TaskRunTransition taskRun(RunStatus from, RunStatus to, RunPhase toPhase) {
    return new TaskRunTransition(
        "tr-1", "wfr-1", TaskType.template, from, RunPhase.pending, to, toPhase);
  }

  private static WorkflowRunTransition workflowRun(RunStatus from, RunStatus to, RunPhase toPhase) {
    return new WorkflowRunTransition("wfr-1", "wf-1", from, RunPhase.running, to, toPhase);
  }

  private static WorkflowRunEntity run(Map<String, String> labels) {
    WorkflowRunEntity entity = new WorkflowRunEntity();
    entity.setId("wfr-1");
    entity.setWorkflowRef("wf-1");
    entity.setStatus(RunStatus.succeeded);
    entity.setPhase(RunPhase.completed);
    entity.setCreationDate(new Date(0));
    entity.setLabels(labels);
    return entity;
  }

  private static EventOutboxEntity row() {
    EventOutboxEntity row = new EventOutboxEntity();
    row.setId("row-1");
    row.setRefType(EventOutboxEntity.REF_TYPE_WORKFLOWRUN);
    row.setTo(new EventOutboxEntity.RunState(RunStatus.succeeded, RunPhase.completed));
    row.setOccurredAt(new Date(5000));
    return row;
  }
}
