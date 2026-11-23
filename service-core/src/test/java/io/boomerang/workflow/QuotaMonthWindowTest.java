package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mongodb.ExplainVerbosity;
import com.mongodb.client.MongoCollection;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.TriggerEnum;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.StatusReason;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowSubmitRequest;
import io.boomerang.core.audit.AuditEventEntity;
import io.boomerang.core.audit.AuditQueryService;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.Quotas;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * The monthly quota figures read the whole UTC calendar month, its last day included: from the
 * first instant of the month up to, not including, the first instant of the next. The entry a
 * refused submit left is a run document that was never a run: the month's count leaves it out.
 */
class QuotaMonthWindowTest extends AbstractEngineIntegrationTest {

  private static final String WORKSPACE = "quota-month-window-ws";

  @Autowired private AuditQueryService auditQueryService;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private WorkflowService workflowService;
  @Autowired private WorkflowRunService workflowRunService;
  @Autowired private WorkspaceService workspaceService;
  @Autowired private QuotaService quotaService;

  @Test
  void theWindowIsTheWholeUtcMonth() {
    QuotaService.MonthWindow february = QuotaService.MonthWindow.of(LocalDate.of(2026, 2, 10));

    assertEquals(Instant.parse("2026-02-01T00:00:00Z"), february.from().toInstant());
    assertEquals(Instant.parse("2026-03-01T00:00:00Z"), february.to().toInstant());
  }

  @Test
  void aRunOnTheLastDayOfTheMonthIsCounted() {
    runEvent("quota-window-last-day", "2026-02-28T23:30:00Z", 60_000L);
    runEvent("quota-window-next-month", "2026-03-01T00:00:00Z", 1_000L);
    QuotaService.MonthWindow february = QuotaService.MonthWindow.of(LocalDate.of(2026, 2, 10));

    AuditQueryService.RunTotals totals =
        auditQueryService.runTotals(WORKSPACE, february.from(), february.to());

    assertEquals(1, totals.runs(), "the last day's run counts; the next month's does not");
    assertEquals(60_000L, totals.duration());
  }

  @Test
  void theEntriesRefusedSubmitsLeftAreNotRunsOfTheMonth() {
    Quotas quotas = new Quotas();
    quotas.setMaxWorkflowRunMonthly(2);
    WorkspaceRequest workspaceRequest = new WorkspaceRequest();
    workspaceRequest.setName("quota-month-refused");
    workspaceRequest.setDisplayName("quota-month-refused");
    workspaceRequest.setQuotas(quotas);
    String workspace = workspaceService.create(workspaceRequest).getName();
    seedGlobalTask("quota-month-refused-task");
    setFeatureSetting("globalParameters", false);
    setFeatureSetting("workspaceParameters", false);
    workflowService.create(
        workspace, runnableWorkflow("quota-month-refused-workflow", "quota-month-refused-task"));
    String workflowRef =
        relationshipService
            .refsInWorkspace(
                RelationshipType.WORKFLOW,
                workspace,
                Optional.of(List.of("quota-month-refused-workflow")))
            .get(0);
    for (int i = 0; i < 3; i++) {
      workflowService.recordRefusedSubmit(
          workspace,
          workflowRef,
          request(),
          "quota-month-refused-schedule",
          "Schedule \"nightly\" fired at 02:00 UTC and no run started after 3 attempts. Test",
          new IllegalStateException("test"));
    }
    setFeatureSetting("workspaceQuotas", true);
    try {
      assertEquals(
          3,
          workflowRunRepository.findAll().stream()
              .filter(run -> workflowRef.equals(run.getWorkflowRef()))
              .filter(run -> StatusReason.SUBMIT_REFUSED.equals(run.getStatusReason()))
              .count());
      assertEquals(0, quotaService.current(workspace).getCurrentRuns(), "three entries, no runs");

      // The month's two runs are both still to be had.
      WorkflowRun first =
          workflowService.submit(workspace, "quota-month-refused-workflow", request(), false);
      WorkflowRun second =
          workflowService.submit(workspace, "quota-month-refused-workflow", request(), false);

      assertEquals(2, quotaService.current(workspace).getCurrentRuns());
      BoomerangException spent =
          assertThrows(
              BoomerangException.class,
              () -> workflowService.submit(workspace, "quota-month-refused-workflow", request(), false));
      assertEquals("QUOTA_EXCEEDED", spent.getReason());
      assertEquals("Number of runs (executions)", spent.getArgs()[0]);
      workflowRunService.cancel(first.getId());
      workflowRunService.cancel(second.getId());
    } finally {
      setFeatureSetting("workspaceQuotas", false);
    }
  }

  // The count that takes the entries back out reads the keys of its own small index and no run.
  @Test
  void theRefusedEntriesAreCountedFromTheirOwnIndex() {
    MongoCollection<Document> runs =
        mongoTemplate.getCollection(mongoTemplate.getCollectionName(WorkflowRunEntity.class));
    List<Document> history = new ArrayList<>();
    Date now = new Date();
    for (int i = 0; i < 500; i++) {
      Document run =
          new Document("workflowRef", "quota-month-plan-workflow")
              .append("creationDate", now)
              .append("status", "succeeded")
              .append("phase", "completed");
      if (i < 3) {
        run.append("status", "cancelled").append("statusReason", StatusReason.SUBMIT_REFUSED);
      }
      history.add(run);
    }
    runs.insertMany(history);
    try {
      QuotaService.MonthWindow month = QuotaService.MonthWindow.of(LocalDate.now(ZoneOffset.UTC));
      Document explained =
          runs.find(
                  new Document("workflowRef", new Document("$in", List.of("quota-month-plan-workflow")))
                      .append(
                          "creationDate",
                          new Document("$gte", month.from()).append("$lt", month.to()))
                      .append("statusReason", StatusReason.SUBMIT_REFUSED))
              .projection(new Document("_id", 0).append("workflowRef", 1))
              .explain(ExplainVerbosity.EXECUTION_STATS);
      Document stats = explained.get("executionStats", Document.class);

      assertEquals(3, stats.getInteger("nReturned"));
      assertEquals(0, stats.getInteger("totalDocsExamined"), "counted from index keys alone");
      assertEquals(3, stats.getInteger("totalKeysExamined"), "and only the entries' keys");
      assertTrue(explained.toJson().contains("refused_submit_month"), explained.toJson());
    } finally {
      runs.deleteMany(new Document("workflowRef", "quota-month-plan-workflow"));
    }
  }

  private static WorkflowSubmitRequest request() {
    WorkflowSubmitRequest request = new WorkflowSubmitRequest();
    request.setTrigger(TriggerEnum.manual);
    return request;
  }

  private void runEvent(String runId, String at, long duration) {
    AuditEventEntity event = new AuditEventEntity();
    event.setWorkspaceId(WORKSPACE);
    event.setResourceType("workflowrun");
    event.setResourceId(runId);
    event.setTime(Date.from(Instant.parse(at)));
    event.getPayload().put("duration", duration);
    mongoTemplate.insert(event);
  }
}
