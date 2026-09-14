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
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Workspace insights rolled up from the audit trail's workflow-run events, so deleted Workflows
 * and WorkflowRuns still count. Reads events with {@code resourceType="workflowrun"} whose
 * payload carries the run facts ({@code workflowRef}, {@code workflowName}, {@code duration},
 * {@code status}, {@code phase}) — written by the engine's transition listener ({@code
 * core.audit.WorkflowRunAuditBridge}): a CREATE event on the run's first status change and an
 * UPDATE event when it completes. The events per run are collapsed to one summary — the earliest
 * dates it, the latest carries its current facts.
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

    // Time-ascending, so per run the first event is its creation and the last its latest state.
    List<AuditEventEntity> events =
        auditQueryService.findByWorkspaceAndResourceType(
            team, "workflowrun", from, to, wfRefs.map(refs -> "workflowRef"), wfRefs);
    Map<String, List<AuditEventEntity>> byRun = new LinkedHashMap<>();
    for (AuditEventEntity event : events) {
      byRun.computeIfAbsent(event.getResourceId(), id -> new ArrayList<>()).add(event);
    }

    WorkflowRunInsight insight = new WorkflowRunInsight();
    long totalDuration = 0L;
    long inFlight = 0L;
    List<WorkflowRunSummary> summaries = new LinkedList<>();
    for (List<AuditEventEntity> runEvents : byRun.values()) {
      AuditEventEntity first = runEvents.get(0);
      AuditEventEntity latest = runEvents.get(runEvents.size() - 1);
      long duration = payloadLong(latest, "duration");
      totalDuration += duration;
      if (!RunPhase.completed.getPhase().equals(payloadString(latest, "phase"))) {
        inFlight++;
      }

      WorkflowRunSummary summary = new WorkflowRunSummary();
      summary.setCreationDate(first.getTime());
      summary.setDuration(duration);
      summary.setStatus(RunStatus.getRunStatus(payloadString(latest, "status")));
      summary.setWorkflowRef(payloadString(latest, "workflowRef"));
      summary.setWorkflowName(payloadString(latest, "workflowName"));
      summaries.add(summary);
    }
    insight.setTotalRuns((long) byRun.size());
    insight.setConcurrentRuns(inFlight);
    insight.setTotalDuration(totalDuration);
    insight.setMedianDuration(byRun.isEmpty() ? 0L : totalDuration / byRun.size());
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
