package io.boomerang.flow.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.boomerang.flow.sdk.model.Action;
import io.boomerang.flow.sdk.model.ActionStatus;
import io.boomerang.flow.sdk.model.ActionType;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/** Each action route's method, path, query and body, and how each answer is read. */
class ActionsTest {

  private static final String ACTIONS = StubServer.API + "/workspace/ws/action";

  private final StubServer stub = new StubServer();
  private final Actions actions = stub.flow.actions("ws");

  @AfterEach
  void verify() {
    stub.server.verify();
  }

  @Test
  void getReadsTheActionWithItsDecisions() {
    stub.server
        .expect(requestTo(ACTIONS + "/a1"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer secret"))
        .andRespond(
            withSuccess(
                "{\"id\":\"a1\",\"type\":\"approval\",\"status\":\"submitted\","
                    + "\"workflowRunRef\":\"r1\",\"taskRunRef\":\"t1\",\"taskName\":\"review\","
                    + "\"numberOfApprovers\":2,\"approvalsRequired\":2,\"numberOfApprovals\":1,"
                    + "\"creationDate\":\"2026-10-01T10:00:00.000+00:00\","
                    + "\"actioners\":[{\"approverId\":\"u1\",\"approved\":true,"
                    + "\"comments\":\"fine\",\"date\":\"2026-10-01T10:05:00.000+00:00\"}],"
                    + "\"somethingNewer\":true}",
                MediaType.APPLICATION_JSON));

    Action action = actions.get("a1");

    assertThat(action.type()).isEqualTo(ActionType.approval);
    assertThat(action.status()).isEqualTo(ActionStatus.submitted);
    assertThat(action.workflowRunRef()).isEqualTo("r1");
    assertThat(action.approvalsRequired()).isEqualTo(2L);
    assertThat(action.numberOfApprovals()).isEqualTo(1L);
    assertThat(action.creationDate()).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
    assertThat(action.actioners()).hasSize(1);
    assertThat(action.actioners().getFirst().approved()).isTrue();
    assertThat(action.actioners().getFirst().comments()).isEqualTo("fine");
  }

  @Test
  void queryMapsEveryFilterToTheRoutesParametersAndPages() {
    stub.server
        .expect(
            requestTo(
                ACTIONS
                    + "/query?types=approval&statuses=submitted&statuses=approved&workflows=wf-1"
                    + "&fromDate=1700000000000&toDate=1700003600000&page=0&limit=20&order=ASC"
                    + "&sort=creationDate"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                StubServer.page(
                    0, 20, 21, false, "{\"id\":\"a1\",\"type\":\"manual\",\"status\":\"later\"}"),
                MediaType.APPLICATION_JSON));
    stub.server
        .expect(
            requestTo(
                ACTIONS
                    + "/query?types=approval&statuses=submitted&statuses=approved&workflows=wf-1"
                    + "&fromDate=1700000000000&toDate=1700003600000&page=1&limit=20&order=ASC"
                    + "&sort=creationDate"))
        .andRespond(
            withSuccess(StubServer.page(1, 20, 21, true, "{\"id\":\"a2\"}"), MediaType.APPLICATION_JSON));

    Page<Action> page =
        actions.query(
            ActionQuery.all()
                .types(ActionType.approval)
                .statuses(ActionStatus.submitted, ActionStatus.approved)
                .workflows("wf-1")
                .from(Instant.ofEpochMilli(1700000000000L))
                .to(Instant.ofEpochMilli(1700003600000L))
                .page(0, 20)
                .order(Order.ASC)
                .sort("creationDate"));

    assertThat(page.total()).isEqualTo(21);
    assertThat(page.content().getFirst().type()).isEqualTo(ActionType.manual);
    assertThat(page.content().getFirst().status()).isEqualTo(ActionStatus.unknown);
    assertThat(page.next()).get().extracting(next -> next.content().getFirst().id()).isEqualTo("a2");
  }

  @Test
  void decidePutsEveryDecisionAsOneRequestAndReadsNothing() {
    stub.server
        .expect(requestTo(ACTIONS))
        .andExpect(method(HttpMethod.PUT))
        .andExpect(header("Content-Type", "application/json"))
        .andExpect(
            StubServer.jsonBody(
                "[{\"id\":\"a1\",\"approved\":true,\"comments\":\"ship it\"},"
                    + "{\"id\":\"a2\",\"approved\":false,\"comments\":\"needs the report\"}]"))
        .andRespond(withSuccess());

    actions.decide(
        ActionDecision.approve("a1", "ship it"), ActionDecision.reject("a2", "needs the report"));
  }
}
