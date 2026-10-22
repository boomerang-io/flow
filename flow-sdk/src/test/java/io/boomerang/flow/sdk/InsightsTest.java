package io.boomerang.flow.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.boomerang.flow.sdk.model.RunStatus;
import io.boomerang.flow.sdk.model.TaskType;
import io.boomerang.flow.sdk.model.WorkflowRunInsightSummary;
import io.boomerang.flow.sdk.model.WorkflowRunInsightTasks;
import io.boomerang.flow.sdk.model.WorkflowRunInsightWorkflowDetail;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

/** Each Insights route's parameters - the part of the query it takes - and how its answer reads. */
class InsightsTest {

  private static final String INSIGHTS = StubServer.API + "/workspace/ws/insights";
  private static final String PERIOD = "fromDate=1700000000000&toDate=1700003600000";

  private final StubServer stub = new StubServer();
  private final Insights insights = stub.flow.insights("ws");

  // Every field set, so each route's test shows which it sends.
  private static InsightsQuery everything() {
    return InsightsQuery.period(
            Instant.ofEpochMilli(1700000000000L), Instant.ofEpochMilli(1700003600000L))
        .workflows("wf-1")
        .label("env", "prod")
        .statuses(RunStatus.failed)
        .triggers("schedule")
        .tasks("review")
        .types(TaskType.ai)
        .groupByLabel("boomerang.io/item")
        .bucket(Bucket.day)
        .spanByLabel("team.io/batch")
        .slowest(5);
  }

  @AfterEach
  void verify() {
    stub.server.verify();
  }

  @Test
  void summarySendsThePeriodWorkflowsLabelsStatusesAndTriggers() {
    stub.server
        .expect(
            requestTo(
                INSIGHTS
                    + "?"
                    + PERIOD
                    + "&labels=env%3Dprod&workflows=wf-1&statuses=failed&triggers=schedule"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                "{\"from\":\"2026-07-03T00:00:00Z\",\"to\":\"2026-10-01T00:00:00Z\","
                    + "\"totals\":{\"runs\":12,\"succeeded\":10,\"failed\":2,"
                    + "\"successRate\":0.83,\"byTrigger\":{\"manual\":12}},"
                    + "\"previous\":{\"runs\":0},"
                    + "\"daily\":[{\"date\":\"2026-10-01\",\"succeeded\":1}],"
                    + "\"workflows\":[{\"workflowRef\":\"wf-1\",\"runs\":12,\"recent\":[]}],"
                    + "\"usage\":{\"aiTaskRuns\":3,\"totalTokens\":900,"
                    + "\"byModel\":[{\"model\":\"m\"}]}}",
                MediaType.APPLICATION_JSON));

    WorkflowRunInsightSummary summary = insights.summary(everything());

    assertThat(summary.totals().runs()).isEqualTo(12);
    assertThat(summary.totals().successRate()).isEqualTo(0.83);
    assertThat(summary.totals().byTrigger()).containsEntry("manual", 12L);
    assertThat(summary.previous().successRate()).isNull();
    assertThat(summary.daily().getFirst().date()).isEqualTo("2026-10-01");
    assertThat(summary.workflows().getFirst().workflowRef()).isEqualTo("wf-1");
    assertThat(summary.usage().byModel().getFirst().model()).isEqualTo("m");
  }

  @Test
  void tasksSendsThePeriodFiltersGroupingBucketSpanAndSlowest() {
    stub.server
        .expect(
            requestTo(
                INSIGHTS
                    + "/tasks?"
                    + PERIOD
                    + "&labels=env%3Dprod&workflows=wf-1&tasks=review&types=ai"
                    + "&groupBy=label%3Aboomerang.io%2Fitem&bucket=day"
                    + "&spanBy=label%3Ateam.io%2Fbatch&slowest=5"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                "{\"groupBy\":\"label:boomerang.io/item\",\"bucket\":\"day\","
                    + "\"truncated\":false,\"rows\":[{\"key\":\"item-1\","
                    + "\"bucket\":\"2026-10-01T00:00:00Z\",\"taskRuns\":4,\"succeeded\":4,"
                    + "\"p95Duration\":3000,\"spans\":2,\"p50Span\":9000,\"totalTokens\":120}],"
                    + "\"slowest\":[{\"taskRunRef\":\"t9\",\"name\":\"review\","
                    + "\"duration\":8000}]}",
                MediaType.APPLICATION_JSON));

    WorkflowRunInsightTasks tasks = insights.tasks(everything());

    WorkflowRunInsightTasks.Row row = tasks.rows().getFirst();
    assertThat(row.key()).isEqualTo("item-1");
    assertThat(row.bucket()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
    assertThat(row.taskRuns()).isEqualTo(4);
    assertThat(row.spans()).isEqualTo(2);
    assertThat(row.totalTokens()).isEqualTo(120);
    assertThat(row.cost()).isNull();
    assertThat(tasks.slowest().getFirst().duration()).isEqualTo(8000);
  }

  @Test
  void workflowSendsThePeriodAndLabelsAlone() {
    stub.server
        .expect(requestTo(INSIGHTS + "/workflow/analysis?" + PERIOD + "&labels=env%3Dprod"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                "{\"workflowRef\":\"wf-1\",\"workflowName\":\"analysis\",\"runs\":12,"
                    + "\"retriedRuns\":1,"
                    + "\"tasks\":[{\"name\":\"review\",\"runs\":12,\"p50Duration\":2000}],"
                    + "\"failures\":[{\"status\":\"failed\",\"taskName\":\"review\","
                    + "\"count\":2}]}",
                MediaType.APPLICATION_JSON));

    WorkflowRunInsightWorkflowDetail detail = insights.workflow("analysis", everything());

    assertThat(detail.retriedRuns()).isEqualTo(1);
    assertThat(detail.tasks().getFirst().p50Duration()).isEqualTo(2000);
    assertThat(detail.failures().getFirst().count()).isEqualTo(2);
  }

  @Test
  void theDefaultQuerySendsNothingAndTheServerAppliesItsPeriod() {
    stub.server
        .expect(requestTo(INSIGHTS))
        .andExpect(method(HttpMethod.GET))
        .andRespond(withSuccess("{\"totals\":{\"runs\":0}}", MediaType.APPLICATION_JSON));

    assertThat(insights.summary(InsightsQuery.all()).totals().runs()).isZero();
  }
}
