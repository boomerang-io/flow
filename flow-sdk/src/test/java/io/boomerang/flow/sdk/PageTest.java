package io.boomerang.flow.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.boomerang.flow.sdk.model.WorkflowRun;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/** A page reads the one after it with the same query, until the last. */
class PageTest {

  private static final String QUERY = StubServer.API + "/workspace/ws/workflowrun/query";

  private final StubServer stub = new StubServer();

  @Test
  void nextReadsTheFollowingPageWithTheSameQueryAndStopsAtTheLast() {
    stub.server
        .expect(requestTo(QUERY + "?labels=env%3Dprod&page=0&limit=1"))
        .andRespond(
            withSuccess(
                StubServer.page(0, 1, 2, false, "{\"id\":\"r1\"}"), MediaType.APPLICATION_JSON));
    stub.server
        .expect(requestTo(QUERY + "?labels=env%3Dprod&page=1&limit=1"))
        .andRespond(
            withSuccess(
                StubServer.page(1, 1, 2, true, "{\"id\":\"r2\"}"), MediaType.APPLICATION_JSON));

    RunQuery query = RunQuery.all().label("env", "prod").page(0, 1);
    Page<WorkflowRun> first = stub.flow.runs("ws").query(query);
    Optional<Page<WorkflowRun>> second = first.next();

    assertThat(first.content()).extracting(WorkflowRun::id).containsExactly("r1");
    assertThat(second).isPresent();
    assertThat(second.get().content()).extracting(WorkflowRun::id).containsExactly("r2");
    assertThat(second.get().next()).isEmpty();
    stub.server.verify();
  }

  @Test
  void aPageReadOutsideAQueryHasNoNext() {
    Page<WorkflowRun> page =
        WireFormat.JSON.readValue(
            StubServer.page(0, 10, 1, false, "{\"id\":\"r1\"}"), Runs.RUN_PAGE);

    assertThat(page.next()).isEmpty();
    assertThat(page.totalPages()).isEqualTo(2);
    assertThat(page.size()).isEqualTo(10);
  }
}
