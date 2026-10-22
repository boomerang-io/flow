package io.boomerang.flow.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.boomerang.flow.sdk.model.RetryScope;
import io.boomerang.flow.sdk.model.RunParam;
import io.boomerang.flow.sdk.model.RunPhase;
import io.boomerang.flow.sdk.model.RunStatus;
import io.boomerang.flow.sdk.model.WorkflowRun;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import tools.jackson.databind.JsonNode;

/** Each workflow-run route's method, path, query, body and token, and how each answer is read. */
class RunsTest {

  private static final String RUNS = StubServer.API + "/workspace/ws/workflowrun";
  private static final String SUBMIT = StubServer.API + "/workspace/ws/workflow/analysis/submit";

  private final StubServer stub = new StubServer();
  private final Runs runs = stub.flow.runs("ws");

  @AfterEach
  void verify() {
    stub.server.verify();
  }

  private static JsonNode json(String json) {
    return WireFormat.JSON.readTree(json);
  }

  @Test
  void submitPostsTheRequestToTheWorkflowAndReadsTheRun() {
    stub.server
        .expect(requestTo(SUBMIT + "?start=true"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer secret"))
        .andExpect(header("Content-Type", "application/json"))
        .andExpect(
            StubServer.jsonBody(
                "{\"params\":[{\"name\":\"depth\",\"value\":3}],"
                    + "\"labels\":{\"team.io/batch\":\"b-7\"},\"annotations\":{},"
                    + "\"workflowVersion\":2}"))
        .andRespond(
            withSuccess(
                "{\"id\":\"r1\",\"status\":\"ready\",\"phase\":\"queued\","
                    + "\"workflowRef\":\"analysis\","
                    + "\"creationDate\":\"2026-10-01T10:00:00.000+00:00\","
                    + "\"labels\":{\"team.io/batch\":\"b-7\"}}",
                MediaType.APPLICATION_JSON));

    WorkflowRun run =
        runs.submit(
            "analysis",
            SubmitRequest.of(List.of(RunParam.of("depth", 3)), Map.of("team.io/batch", "b-7"))
                .workflowVersion(2));

    assertThat(run.id()).isEqualTo("r1");
    assertThat(run.status()).isEqualTo(RunStatus.ready);
    assertThat(run.phase()).isEqualTo(RunPhase.queued);
    assertThat(run.creationDate()).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
    assertThat(run.labels()).containsEntry("team.io/batch", "b-7");
    assertThat(run.tasks()).isNull();
  }

  @Test
  void submitWithoutStartParksTheRun() {
    stub.server
        .expect(requestTo(SUBMIT + "?start=false"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(StubServer.jsonBody("{\"params\":[],\"labels\":{},\"annotations\":{}}"))
        .andRespond(
            withSuccess("{\"id\":\"r1\",\"status\":\"ready\"}", MediaType.APPLICATION_JSON));

    assertThat(runs.submit("analysis", SubmitRequest.empty(), false).status())
        .isEqualTo(RunStatus.ready);
  }

  @Test
  void getReadsTheRunWithItsTasks() {
    stub.server
        .expect(requestTo(RUNS + "/r1?withTasks=true"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                "{\"id\":\"r1\",\"status\":\"succeeded\",\"phase\":\"completed\","
                    + "\"duration\":1200,\"results\":[{\"name\":\"score\",\"value\":0.9}],"
                    + "\"tasks\":[{\"id\":\"t1\",\"name\":\"review\",\"type\":\"template\","
                    + "\"status\":\"succeeded\",\"attempt\":2,\"unknownField\":1}]}",
                MediaType.APPLICATION_JSON));

    WorkflowRun run = runs.get("r1");

    assertThat(run.duration()).isEqualTo(1200L);
    assertThat(run.results().getFirst().value()).isEqualTo(0.9);
    assertThat(run.tasks()).hasSize(1);
    assertThat(run.tasks().getFirst().name()).isEqualTo("review");
    assertThat(run.tasks().getFirst().attempt()).isEqualTo(2);
  }

  @Test
  void getWithoutTasksAsksForTheRunAlone() {
    stub.server
        .expect(requestTo(RUNS + "/r1?withTasks=false"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess("{\"id\":\"r1\",\"status\":\"running\"}", MediaType.APPLICATION_JSON));

    assertThat(runs.get("r1", false).tasks()).isNull();
  }

  @Test
  void startPausesAndResumesPutTheRouteWithNoBodyAndReadTheRunAfter() {
    for (String route : List.of("start", "pause", "resume")) {
      stub.server
          .expect(requestTo(RUNS + "/r1/" + route))
          .andExpect(method(HttpMethod.PUT))
          .andExpect(header("Authorization", "Bearer secret"))
          .andExpect(
              request ->
                  assertThat(((MockClientHttpRequest) request).getBodyAsBytes()).isEmpty())
          .andRespond(
              withSuccess("{\"id\":\"r1\",\"status\":\"running\"}", MediaType.APPLICATION_JSON));
    }

    assertThat(runs.start("r1").status()).isEqualTo(RunStatus.running);
    assertThat(runs.pause("r1").id()).isEqualTo("r1");
    assertThat(runs.resume("r1").id()).isEqualTo("r1");
  }

  @Test
  void cancelDeletesTheRunsCancelRouteAndReadsTheRunAfter() {
    stub.server
        .expect(requestTo(RUNS + "/r1/cancel"))
        .andExpect(method(HttpMethod.DELETE))
        .andRespond(
            withSuccess("{\"id\":\"r1\",\"status\":\"cancelled\"}", MediaType.APPLICATION_JSON));

    assertThat(runs.cancel("r1").status()).isEqualTo(RunStatus.cancelled);
  }

  @Test
  void retryPutsTheScopeWithItsOverridesAndReadsTheNewRun() {
    stub.server
        .expect(requestTo(RUNS + "/r1/retry"))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(header("Content-Type", "application/json"))
        .andExpect(
            StubServer.jsonBody(
                "{\"scope\":\"nodes\",\"nodes\":[\"review\",\"publish\"],"
                    + "\"params\":[{\"name\":\"depth\",\"value\":5}],"
                    + "\"labels\":{\"team.io/retry\":\"yes\"}}"))
        .andRespond(
            withSuccess(
                "{\"id\":\"r2\",\"status\":\"ready\",\"trigger\":\"retry\","
                    + "\"retryFrom\":{\"scope\":\"nodes\",\"nodes\":[\"review\",\"publish\"]}}",
                MediaType.APPLICATION_JSON));

    WorkflowRun run =
        runs.retry(
            "r1",
            Retry.nodes("review", "publish").param("depth", 5).label("team.io/retry", "yes"));

    assertThat(run.id()).isEqualTo("r2");
    assertThat(run.retryFrom().scope()).isEqualTo(RetryScope.nodes);
    assertThat(run.retryFrom().nodes()).containsExactly("review", "publish");
  }

  @Test
  void retryScopesSerialiseAsTheServersRequest() {
    assertThat((JsonNode) WireFormat.JSON.valueToTree(Retry.all()))
        .isEqualTo(json("{\"scope\":\"all\",\"nodes\":[],\"params\":[],\"labels\":{}}"));
    assertThat((JsonNode) WireFormat.JSON.valueToTree(Retry.failed()))
        .isEqualTo(json("{\"scope\":\"failed\",\"nodes\":[],\"params\":[],\"labels\":{}}"));
  }

  @Test
  void queryMapsEveryFilterToTheRoutesParametersFullyEncoded() {
    stub.server
        .expect(
            requestTo(
                RUNS
                    + "/query?labels=team.io%2Fanalysis%3Dx%2Cy&labels=env%3Dprod"
                    + "&workflows=wf-1&fromDate=1700000000000&toDate=1700003600000"
                    + "&statuses=running&statuses=waiting&phase=running&workflowruns=r9"
                    + "&triggers=manual&page=0&limit=50&order=DESC"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                StubServer.page(0, 50, 1, true, "{\"id\":\"r1\",\"status\":\"running\"}"),
                MediaType.APPLICATION_JSON));

    Page<WorkflowRun> page =
        runs.query(
            RunQuery.all()
                .labels("team.io/analysis=x,y")
                .label("env", "prod")
                .workflows("wf-1")
                .from(Instant.ofEpochMilli(1700000000000L))
                .to(Instant.ofEpochMilli(1700003600000L))
                .statuses(RunStatus.running, RunStatus.waiting)
                .phases(RunPhase.running)
                .runs("r9")
                .triggers("manual")
                .page(0, 50)
                .order(Order.DESC));

    assertThat(page.content()).extracting(WorkflowRun::id).containsExactly("r1");
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.isLast()).isTrue();
    assertThat(page.next()).isEmpty();
  }

  @Test
  void queryWithoutFiltersSendsNone() {
    stub.server
        .expect(requestTo(RUNS + "/query"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess(StubServer.page(0, 0, 0, true), MediaType.APPLICATION_JSON));

    assertThat(runs.query(RunQuery.all()).isEmpty()).isTrue();
  }

  @Test
  void countSendsTheRoutesFiltersOnlyAndReadsTheCountByStatus() {
    stub.server
        .expect(
            requestTo(
                RUNS + "/count?labels=env%3Dprod&workflows=wf-1&fromDate=1700000000000"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                "{\"status\":{\"succeeded\":7,\"failed\":2}}", MediaType.APPLICATION_JSON));

    var count =
        runs.count(
            RunQuery.all()
                .label("env", "prod")
                .workflows("wf-1")
                .from(Instant.ofEpochMilli(1700000000000L))
                .statuses(RunStatus.failed)
                .page(3, 10));

    assertThat(count.of(RunStatus.succeeded)).isEqualTo(7);
    assertThat(count.of(RunStatus.cancelled)).isZero();
    assertThat(count.total()).isEqualTo(9);
  }

  @Test
  void anUnknownStatusReadsAsUnknown() {
    stub.server
        .expect(requestTo(RUNS + "/r1?withTasks=true"))
        .andRespond(
            withSuccess(
                "{\"id\":\"r1\",\"status\":\"hibernating\",\"phase\":\"frozen\"}",
                MediaType.APPLICATION_JSON));

    WorkflowRun run = runs.get("r1");

    assertThat(run.status()).isEqualTo(RunStatus.unknown);
    assertThat(run.phase()).isEqualTo(RunPhase.unknown);
  }
}
