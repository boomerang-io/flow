package io.boomerang.dispatcher.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.boomerang.dispatcher.sdk.model.DispatcherRegistrationRequest;
import io.boomerang.dispatcher.sdk.model.RunPhase;
import io.boomerang.dispatcher.sdk.model.RunResult;
import io.boomerang.dispatcher.sdk.model.RunStatus;
import io.boomerang.dispatcher.sdk.model.TaskDeletion;
import io.boomerang.dispatcher.sdk.model.TaskRun;
import io.boomerang.dispatcher.sdk.model.TaskRunEndRequest;
import io.boomerang.dispatcher.sdk.model.TaskRunStartRequest;
import io.boomerang.dispatcher.sdk.model.TaskType;
import io.boomerang.dispatcher.sdk.model.WorkflowRun;
import io.boomerang.dispatcher.sdk.model.WorkspaceReleaseQuery;
import io.boomerang.dispatcher.sdk.model.WorkspaceReleaseResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** Each route's method, path, query, body and token, and how each answer is read. */
class DispatcherClientTest {

  private static final String ENGINE = "http://engine:7700/api/v1/dispatcher";

  private MockRestServiceServer server;
  private DispatcherClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    client = new DispatcherClient(builder.build(), "http://engine:7700/", "secret");
  }

  // The body as JSON, compared as a tree so field order does not matter and absent fields do.
  private static RequestMatcher jsonBody(String expected) {
    return request ->
        assertThat(WireFormat.JSON.readTree(((MockClientHttpRequest) request).getBodyAsBytes()))
            .isEqualTo(WireFormat.JSON.readTree(expected));
  }

  @Test
  void registerPostsWhoTheDispatcherIsAndReturnsItsId() {
    server
        .expect(requestTo(ENGINE + "/register"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer secret"))
        .andExpect(
            jsonBody(
                "{\"name\":\"kube\",\"host\":\"pod-1\",\"taskTypes\":[\"template\"],"
                    + "\"workflowAnnotations\":[],\"taskAnnotations\":[]}"))
        .andRespond(withSuccess("d-1", MediaType.TEXT_PLAIN));

    assertThat(client.register(new DispatcherRegistrationRequest("kube", "pod-1", List.of("template"))))
        .isEqualTo("d-1");
    server.verify();
  }

  @Test
  void anIdAnsweredAsAJsonStringIsUnquoted() {
    server.expect(requestTo(ENGINE + "/register")).andRespond(withSuccess("\"d-1\"", MediaType.APPLICATION_JSON));

    assertThat(client.register(new DispatcherRegistrationRequest("kube", "pod-1", List.of())))
        .isEqualTo("d-1");
  }

  @Test
  void aTaskPollSendsItsLimitAndFiltersFullyEncoded() {
    server
        .expect(
            requestTo(
                ENGINE
                    + "/d-1/tasks?limit=3&type=template%2Ccustom&task=echo%2A"
                    + "&workflowLabel=team%3Dplatform%2Bops%2Cflow"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer secret"))
        .andRespond(withNoContent());

    List<TaskRun> runs =
        client.pollTasks(
            "d-1",
            3,
            TaskFilter.none()
                .types("template", "custom")
                .tasks("echo*")
                .workflowLabel("team", "platform+ops", "flow"));

    assertThat(runs).isEmpty();
    server.verify();
  }

  @Test
  void aTaskPollWithNoLimitOrFilterSendsNoQuery() {
    server.expect(requestTo(ENGINE + "/d-1/tasks")).andRespond(withNoContent());

    assertThat(client.pollTasks("d-1", null, null)).isEmpty();
    server.verify();
  }

  @Test
  void aTaskPollReadsWhatTheEngineHandsOut() {
    server
        .expect(requestTo(ENGINE + "/d-1/tasks?limit=1"))
        .andRespond(
            withSuccess(
                "[{\"id\":\"t-1\",\"type\":\"template\",\"phase\":\"queued\",\"status\":\"ready\","
                    + "\"creationDate\":\"2026-10-08T09:00:00.000+00:00\",\"timeout\":30,"
                    + "\"params\":[{\"name\":\"greeting\",\"value\":\"hello\"}],"
                    + "\"spec\":{\"image\":\"alpine\",\"deletion\":\"OnSuccess\"}}]",
                MediaType.APPLICATION_JSON));

    TaskRun run = client.pollTasks("d-1", 1, TaskFilter.none()).get(0);

    assertThat(run.isRunOrder()).isTrue();
    assertThat(run.getType()).isEqualTo(TaskType.template);
    assertThat(run.getCreationDate()).isEqualTo(Instant.parse("2026-10-08T09:00:00Z"));
    assertThat(run.param("greeting")).isEqualTo("hello");
    assertThat(run.getSpec().getDeletion()).isEqualTo(TaskDeletion.OnSuccess);
  }

  @Test
  void fieldsAndValuesAddedToTheEngineLaterAreTolerated() {
    // The compatibility policy lets the engine add response fields and enum values on v1.
    server
        .expect(requestTo(ENGINE + "/d-1/tasks"))
        .andRespond(
            withSuccess(
                "[{\"id\":\"t-1\",\"type\":\"wasm\",\"phase\":\"parked\",\"status\":\"held\","
                    + "\"priority\":7,\"spec\":{\"deletion\":\"Sometimes\",\"gpu\":true}}]",
                MediaType.APPLICATION_JSON));

    TaskRun run = client.pollTasks("d-1", null, null).get(0);

    assertThat(run.getType()).isEqualTo(TaskType.unknown);
    assertThat(run.getPhase()).isEqualTo(RunPhase.unknown);
    assertThat(run.getStatus()).isEqualTo(RunStatus.unknown);
    assertThat(run.getSpec().getDeletion()).isNull();
    assertThat(run.isRunOrder()).isFalse();
    assertThat(run.isTerminateOrder()).isFalse();
  }

  @Test
  void aWorkflowPollReadsTheRunsToProvision() {
    server
        .expect(requestTo(ENGINE + "/d-1/workflows"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                "[{\"id\":\"w-1\",\"workflowRef\":\"wf\",\"phase\":\"queued\",\"status\":\"ready\","
                    + "\"workspaces\":[{\"name\":\"shared\",\"type\":\"workflowrun\","
                    + "\"spec\":{\"size\":\"2Gi\"}}]}]",
                MediaType.APPLICATION_JSON));

    WorkflowRun run = client.pollWorkflows("d-1").get(0);

    assertThat(run.getId()).isEqualTo("w-1");
    assertThat(run.getWorkspaces()).singleElement().extracting("type").isEqualTo("workflowrun");
  }

  @Test
  void anEmptyWorkflowPollIsAnEmptyList() {
    server.expect(requestTo(ENGINE + "/d-1/workflows")).andRespond(withNoContent());

    assertThat(client.pollWorkflows("d-1")).isEmpty();
  }

  @Test
  void startSendsOnlyTheDispatcherRefAndReturnsTheAnswer() {
    server
        .expect(requestTo(ENGINE + "/taskrun/t-1/start"))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(jsonBody("{\"dispatcherRef\":\"d-1\"}"))
        .andRespond(
            withSuccess(
                "{\"id\":\"t-1\",\"phase\":\"completed\",\"status\":\"cancelled\"}",
                MediaType.APPLICATION_JSON));

    TaskRun answer = client.startTask("t-1", new TaskRunStartRequest("d-1"));

    assertThat(answer.getPhase()).isEqualTo(RunPhase.completed);
  }

  @Test
  void endSendsTheOutcomeWithoutNullFields() {
    // A null the engine reads as a map would be merged into the run; absent fields keep its own.
    server
        .expect(requestTo(ENGINE + "/taskrun/t-1/end"))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(
            jsonBody(
                "{\"status\":\"failed\",\"statusReason\":\"OOMKilled\",\"statusMessage\":\"boom\","
                    + "\"results\":[{\"name\":\"code\",\"value\":\"137\"}],\"dispatcherRef\":\"d-1\"}"))
        .andRespond(withSuccess());

    TaskRunEndRequest end = new TaskRunEndRequest();
    end.setStatus(RunStatus.failed);
    end.setStatusReason("OOMKilled");
    end.setStatusMessage("boom");
    end.setResults(List.of(new RunResult("code", "137")));
    end.setDispatcherRef("d-1");
    client.endTask("t-1", end);

    server.verify();
  }

  @Test
  void heartbeatListsTheTaskRunIds() {
    server
        .expect(requestTo(ENGINE + "/d-1/heartbeat"))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(jsonBody("{\"ids\":[\"t-1\",\"t-2\"]}"))
        .andRespond(withNoContent());

    client.heartbeat("d-1", List.of("t-1", "t-2"));

    server.verify();
  }

  @Test
  void startWorkflowRunSendsAnEmptyRequest() {
    server
        .expect(requestTo(ENGINE + "/workflowrun/w-1/start"))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(jsonBody("{}"))
        .andRespond(withSuccess());

    client.startWorkflowRun("w-1");

    server.verify();
  }

  @Test
  void releasableAsksWithTheHeldOwnersAndReadsTheAnswer() {
    server
        .expect(requestTo(ENGINE + "/workspaces/releasable"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonBody("{\"workflowRunRefs\":[\"r-1\",\"r-2\"],\"workflowRefs\":[\"w-1\"]}"))
        .andRespond(
            withSuccess(
                "{\"workflowRunRefs\":[\"r-2\"],\"workflowRefs\":[]}", MediaType.APPLICATION_JSON));

    WorkspaceReleaseQuery query = new WorkspaceReleaseQuery();
    query.setWorkflowRunRefs(List.of("r-1", "r-2"));
    query.setWorkflowRefs(List.of("w-1"));
    WorkspaceReleaseResponse response = client.releasable(query);

    assertThat(response.getWorkflowRunRefs()).containsExactly("r-2");
    assertThat(response.getWorkflowRefs()).isEmpty();
  }

  @Test
  void aBlankTokenSendsNoAuthorization() {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer anonymous = MockRestServiceServer.bindTo(builder).build();
    DispatcherClient unauthenticated = new DispatcherClient(builder.build(), "http://engine:7700", "");
    anonymous
        .expect(requestTo(ENGINE + "/d-1/heartbeat"))
        .andExpect(headerDoesNotExist("Authorization"))
        .andRespond(withNoContent());

    unauthenticated.heartbeat("d-1", List.of("t-1"));

    anonymous.verify();
  }

  @Test
  void aRefusalSurfacesAsAClientError() {
    server
        .expect(requestTo(ENGINE + "/taskrun/t-1/end"))
        .andRespond(withStatus(HttpStatus.CONFLICT));

    assertThatThrownBy(() -> client.endTask("t-1", new TaskRunEndRequest()))
        .isInstanceOf(HttpClientErrorException.class);
  }
}
