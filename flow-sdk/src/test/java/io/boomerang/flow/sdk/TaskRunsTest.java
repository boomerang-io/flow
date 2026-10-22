package io.boomerang.flow.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.boomerang.flow.sdk.model.RunPhase;
import io.boomerang.flow.sdk.model.RunStatus;
import io.boomerang.flow.sdk.model.TaskRun;
import io.boomerang.flow.sdk.model.TaskType;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/** The task-run query's parameters and how a row is read. */
class TaskRunsTest {

  private static final String TASK_RUNS = StubServer.API + "/workspace/ws/taskrun";

  private final StubServer stub = new StubServer();

  @AfterEach
  void verify() {
    stub.server.verify();
  }

  @Test
  void queryMapsEveryFilterToTheRoutesParameters() {
    stub.server
        .expect(
            requestTo(
                TASK_RUNS
                    + "/query?labels=team.io%2Fbatch%3Db-7&statuses=failed&phase=completed"
                    + "&workflowruns=r1&workflows=wf-1&tasks=review&tasks=publish"
                    + "&fromDate=1700000000000&toDate=1700003600000&page=1&limit=20&order=DESC"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                StubServer.page(
                    1,
                    20,
                    21,
                    true,
                    "{\"id\":\"t1\",\"name\":\"review[2]\",\"type\":\"template\","
                        + "\"status\":\"failed\",\"phase\":\"completed\",\"parentRef\":\"t0\","
                        + "\"index\":2,"
                        + "\"workflowRunRef\":\"r1\",\"startTime\":\"2026-10-01T10:00:00Z\"}"),
                MediaType.APPLICATION_JSON));

    Page<TaskRun> page =
        stub.flow
            .taskRuns("ws")
            .query(
                TaskRunQuery.all()
                    .label("team.io/batch", "b-7")
                    .statuses(RunStatus.failed)
                    .phases(RunPhase.completed)
                    .runs("r1")
                    .workflows("wf-1")
                    .tasks("review", "publish")
                    .from(Instant.ofEpochMilli(1700000000000L))
                    .to(Instant.ofEpochMilli(1700003600000L))
                    .page(1, 20)
                    .order(Order.DESC));

    TaskRun row = page.content().getFirst();
    assertThat(row.type()).isEqualTo(TaskType.template);
    assertThat(row.parentRef()).isEqualTo("t0");
    assertThat(row.index()).isEqualTo(2);
    assertThat(row.params()).isNull();
    assertThat(page.number()).isEqualTo(1);
    assertThat(page.total()).isEqualTo(21);
    assertThat(page.isFirst()).isFalse();
  }
}
