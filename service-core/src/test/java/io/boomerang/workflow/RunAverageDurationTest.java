package io.boomerang.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import io.boomerang.core.audit.AuditEventEntity;
import io.boomerang.engine.AbstractEngineIntegrationTest;
import io.boomerang.workspace.WorkspaceService;
import io.boomerang.workspace.model.CurrentQuotas;
import io.boomerang.workspace.model.Quotas;
import io.boomerang.workspace.model.WorkspaceRequest;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * A workspace's mean run duration this month is the ended runs' total over the runs that ended: a
 * run still going has no duration yet and does not pull the mean down.
 */
class RunAverageDurationTest extends AbstractEngineIntegrationTest {

  @Autowired private WorkspaceService workspaceService;
  @Autowired private QuotaService quotaService;
  @Autowired private MongoTemplate mongoTemplate;

  @Test
  void theMeanIsOverTheRunsThatEnded() {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName("run-average-duration-" + System.nanoTime());
    request.setDisplayName(request.getName());
    request.setQuotas(new Quotas());
    String workspace = workspaceService.create(request).getName();
    event(workspace, "run-average-ended-1", "CREATE", null);
    event(workspace, "run-average-ended-1", "UPDATE", 60_000L);
    event(workspace, "run-average-ended-2", "CREATE", null);
    event(workspace, "run-average-ended-2", "UPDATE", 180_000L);
    event(workspace, "run-average-going", "CREATE", null);
    event(workspace, "run-average-going-too", "CREATE", null);

    CurrentQuotas quotas = quotaService.current(workspace);

    assertThat(quotas.getCurrentRunTotalDuration()).isEqualTo(240_000L);
    assertThat(quotas.getCurrentRunAverageDuration())
        .as("two ended runs, not the four the month holds")
        .isEqualTo(120_000L);
  }

  @Test
  void noRunEndedIsAMeanOfZero() {
    WorkspaceRequest request = new WorkspaceRequest();
    request.setName("run-average-none-" + System.nanoTime());
    request.setDisplayName(request.getName());
    request.setQuotas(new Quotas());
    String workspace = workspaceService.create(request).getName();
    event(workspace, "run-average-only-going", "CREATE", null);

    assertThat(quotaService.current(workspace).getCurrentRunAverageDuration()).isZero();
  }

  private void event(String workspace, String runId, String action, Long duration) {
    AuditEventEntity event = new AuditEventEntity();
    // The trail keys a workspace's events on its id.
    event.setWorkspaceId(workspaceService.get(workspace).getId());
    event.setWorkspaceName(workspace);
    event.setAction(action);
    event.setResourceType("workflowrun");
    event.setResourceId(runId + "-" + workspace);
    event.setTime(new Date());
    if (duration != null) {
      event.getPayload().put("duration", duration);
    }
    mongoTemplate.insert(event);
  }
}
