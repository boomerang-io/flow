package io.boomerang.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.boomerang.core.audit.AuditEventEntity;
import io.boomerang.core.audit.AuditQueryService;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * The monthly quota figures read the whole UTC calendar month, its last day included: from the
 * first instant of the month up to, not including, the first instant of the next.
 */
class QuotaMonthWindowTest extends AbstractEngineIntegrationTest {

  private static final String WORKSPACE = "quota-month-window-ws";

  @Autowired private AuditQueryService auditQueryService;
  @Autowired private MongoTemplate mongoTemplate;

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
