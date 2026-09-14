package io.boomerang.workspace;

import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.model.WorkflowRunInsight;
import io.boomerang.common.model.WorkflowRunSummary;
import io.boomerang.config.ConditionalOnFlowMode;
import io.boomerang.config.FlowMode;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.audit.AuditEventEntity;
import io.boomerang.core.audit.AuditQueryService;
import io.boomerang.core.enums.RelationshipType;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Workspace insights rolled up from the audit trail's workflow-run events, so deleted Workflows
 * and WorkflowRuns still count. Reads events with {@code resourceType="workflowrun"} whose payload
 * carries the run facts ({@code workflowRef}, {@code workflowName}, {@code duration}, {@code
 * status}, {@code phase}) — the run lifecycle emission that writes them rides the engine's
 * transition listener.
 */
@Service
@ConditionalOnFlowMode(FlowMode.STANDALONE)
public class InsightsService {

  private final RelationshipService relationshipService;
  private final AuditQueryService auditQueryService;

  public InsightsService(
      RelationshipService relationshipService, AuditQueryService auditQueryService) {
    this.relationshipService = relationshipService;
    this.auditQueryService = auditQueryService;
  }

  public WorkflowRunInsight get(
      String team,
      Date from,
      Date to,
      Optional<List<String>> workflowRefs,
      Optional<List<String>> statuses) {
    // When Workflows are named, resolve them against the workspace; otherwise take every run
    // event in the workspace (which includes runs of since-deleted Workflows).
    Optional<List<String>> wfRefs = Optional.empty();
    if (workflowRefs.isPresent()) {
      wfRefs =
          Optional.of(
              relationshipService.filter(
                  RelationshipType.WORKFLOW,
                  workflowRefs,
                  Optional.of(RelationshipType.WORKSPACE),
                  Optional.of(List.of(team)),
                  false));
    }

    List<AuditEventEntity> events =
        auditQueryService.findByWorkspaceAndResourceType(
            team, "workflowrun", from, to, wfRefs.map(refs -> "workflowRef"), wfRefs);

    WorkflowRunInsight insight = new WorkflowRunInsight();
    long totalDuration = 0L;
    List<WorkflowRunSummary> summaries = new LinkedList<>();
    for (AuditEventEntity event : events) {
      long duration = payloadLong(event, "duration");
      totalDuration += duration;

      WorkflowRunSummary summary = new WorkflowRunSummary();
      summary.setCreationDate(event.getTime());
      summary.setDuration(duration);
      summary.setStatus(RunStatus.getRunStatus(payloadString(event, "status")));
      summary.setWorkflowRef(payloadString(event, "workflowRef"));
      summary.setWorkflowName(payloadString(event, "workflowName"));
      summaries.add(summary);
    }
    insight.setTotalRuns((long) events.size());
    insight.setConcurrentRuns(
        events.stream()
            .filter(event -> RunPhase.running.getPhase().equals(payloadString(event, "phase")))
            .count());
    insight.setTotalDuration(totalDuration);
    insight.setMedianDuration(events.isEmpty() ? 0L : totalDuration / events.size());
    insight.setRuns(summaries);
    return insight;
  }

  private static String payloadString(AuditEventEntity event, String key) {
    Object value = event.getPayload().get(key);
    return (value != null) ? value.toString() : null;
  }

  private static long payloadLong(AuditEventEntity event, String key) {
    return (event.getPayload().get(key) instanceof Number number) ? number.longValue() : 0L;
  }
}
