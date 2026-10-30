package io.boomerang.flow.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.boomerang.flow.sdk.model.RunPhase;
import io.boomerang.flow.sdk.model.RunStatus;
import io.boomerang.flow.sdk.model.WorkflowRunInsightSummary;
import io.boomerang.flow.sdk.model.WorkflowRunInsightTaskRunPage;
import io.boomerang.flow.sdk.model.WorkflowRunInsightTasks;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/**
 * The platform-wide Insights, read without a workspace: each route goes under {@code /insights},
 * sends the workspaces to aggregate over, and the task-run page carries each row's workspace. A
 * workflow's detail stays a workspace read, and the task-run page a platform-wide one.
 */
class GlobalInsightsTest {

  private static final String INSIGHTS = StubServer.API + "/insights";
  private static final String PERIOD = "fromDate=1700000000000&toDate=1700003600000";

  private final StubServer stub = new StubServer();
  private final Insights insights = stub.flow.insights();

  @AfterEach
  void verify() {
    stub.server.verify();
  }

  @Test
  void theSummaryGoesUnderInsightsWithTheWorkspacesToAggregateOver() {
    stub.server
        .expect(
            requestTo(
                INSIGHTS + "?" + PERIOD + "&labels=env%3Dprod&statuses=failed&workspaces=a&workspaces=b"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                "{\"totals\":{\"runs\":7,\"failed\":7},\"workflows\":[{\"workflowRef\":\"wf-1\"},"
                    + "{\"workflowRef\":\"wf-2\"}]}",
                MediaType.APPLICATION_JSON));

    WorkflowRunInsightSummary summary =
        insights.summary(
            InsightsQuery.period(
                    Instant.ofEpochMilli(1700000000000L), Instant.ofEpochMilli(1700003600000L))
                .label("env", "prod")
                .statuses(RunStatus.failed)
                .workspaces("a", "b"));

    assertThat(summary.totals().runs()).isEqualTo(7);
    assertThat(summary.workflows()).hasSize(2);
  }

  @Test
  void theTaskBreakdownGoesUnderInsightsAndEveryWorkspaceWhenNoneIsNamed() {
    stub.server
        .expect(requestTo(INSIGHTS + "/tasks?groupBy=workflow"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                "{\"groupBy\":\"workflow\",\"rows\":[{\"key\":\"wf-1\",\"taskRuns\":3}]}",
                MediaType.APPLICATION_JSON));

    WorkflowRunInsightTasks tasks = insights.tasks(InsightsQuery.all().groupBy("workflow"));

    assertThat(tasks.rows().getFirst().taskRuns()).isEqualTo(3);
  }

  @Test
  void theTaskRunPageSendsItsFiltersAndCarriesEachRowsWorkspace() {
    stub.server
        .expect(
            requestTo(
                INSIGHTS
                    + "/taskruns?labels=env%3Dprod&statuses=failed&phases=completed&tasks=review"
                    + "&foreach=false&limit=50&page=1&workspaces=a"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                "{\"content\":[{\"id\":\"tr-1\",\"workspace\":\"a\",\"name\":\"review\","
                    + "\"type\":\"template\",\"status\":\"failed\",\"phase\":\"completed\","
                    + "\"creationDate\":\"2026-10-01T00:00:00Z\",\"duration\":1200,\"attempt\":2,"
                    + "\"workflowRef\":\"wf-1\",\"workflowName\":\"analysis\","
                    + "\"workflowRunRef\":\"run-1\",\"labels\":{\"env\":\"prod\"}}],"
                    + "\"number\":1,\"size\":50,\"totalElements\":51,\"totalPages\":2}",
                MediaType.APPLICATION_JSON));

    WorkflowRunInsightTaskRunPage page =
        insights.taskRuns(
            InsightsQuery.all()
                .label("env", "prod")
                .statuses(RunStatus.failed)
                .phases(RunPhase.completed)
                .tasks("review")
                .foreach(false)
                .page(50, 1)
                .workspaces("a"));

    assertThat(page.totalElements()).isEqualTo(51);
    WorkflowRunInsightTaskRunPage.TaskRunRow row = page.content().getFirst();
    assertThat(row.workspace()).isEqualTo("a");
    assertThat(row.attempt()).isEqualTo(2);
    assertThat(row.workflowName()).isEqualTo("analysis");
    assertThat(row.labels()).containsEntry("env", "prod");
  }

  @Test
  void aWorkflowsDetailNeedsAWorkspaceAndTheTaskRunPageRefusesOne() {
    assertThatThrownBy(() -> insights.workflow("wf", InsightsQuery.all()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> stub.flow.insights("ws").taskRuns(InsightsQuery.all()))
        .isInstanceOf(IllegalStateException.class);
  }
}
