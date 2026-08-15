package io.boomerang.workspace;

import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.model.WorkflowRunInsight;
import io.boomerang.common.model.WorkflowRunSummary;
import io.boomerang.config.ConditionalOnFlowMode;
import io.boomerang.config.FlowMode;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.audit.AuditQueryService;
import io.boomerang.core.audit.AuditRecord;
import io.boomerang.core.audit.AuditScope;
import io.boomerang.core.enums.RelationshipType;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

// E8: workspace is a full-mode-only module root per the mode matrix.
@Service
@ConditionalOnFlowMode(FlowMode.STANDALONE)
public class InsightsService {

  private static final Logger LOGGER = LogManager.getLogger();

  private final RelationshipService relationshipService;
  private final AuditQueryService auditQueryService;

  public InsightsService(RelationshipService relationshipService, AuditQueryService auditQueryService) {
    this.relationshipService = relationshipService;
    this.auditQueryService = auditQueryService;
  }

  /*
   * Retrieves information on WorkflowRuns via the Audit Service
   *
   * This ensures it includes deleted Objects, and Insights could be expanded to Workflows
   */
  public WorkflowRunInsight get(
      String team,
      Date from,
      Date to,
      Optional<List<String>> workflowRefs,
      Optional<List<String>> statuses) {
    WorkflowRunInsight insight = new WorkflowRunInsight();

    // Check the queryWorkflows
    List<String> wfRefs = new ArrayList<>();

    // If WorkflowRefs are provided, we can assume that the Workflow is currently active.
    // Otherwise we turn to the audit table.
    if (workflowRefs.isEmpty()) {
      Optional<AuditRecord> teamAE =
          auditQueryService.findFirstByScopeAndSelfName(AuditScope.TEAM, team);
      if (teamAE.isPresent()) {
        LOGGER.debug("Audit Workspace: {}", teamAE.toString());
        List<AuditRecord> workflowAEList =
            auditQueryService.findByScopeAndParent(AuditScope.WORKFLOW, teamAE.get().getId());
        wfRefs = workflowAEList.stream().map(AuditRecord::getSelfRef).toList();
      }
    } else {
      wfRefs =
          relationshipService.filter(
              RelationshipType.WORKFLOW,
              workflowRefs,
              Optional.of(RelationshipType.WORKSPACE),
              Optional.of(List.of(team)),
              false);
    }
    LOGGER.debug("Workflow Refs: {}", wfRefs.toString());
    if (!wfRefs.isEmpty()) {
      // The following logic mirrors the Engine WorkflowRun Insight logic but is based on
      // AuditEntities for WorkfowRun Scope.
      // This ensures we include deleted Workflows and WorkflowRuns in our insights.
      List<AuditRecord> entities =
          auditQueryService.findByScopeAndDateRangeAndDataFieldIn(
              AuditScope.WORKFLOWRUN, from, to, "workflowRef", wfRefs);
      LOGGER.debug("Entities: {}", entities.toString());

      // Collect the Stats
      Long totalDuration = 0L;
      Long duration;
      for (AuditRecord entity : entities) {
        duration = Long.valueOf(entity.getData().get("duration"));
        if (duration != null) {
          totalDuration += duration;
        }
      }
      insight.setTotalRuns(Long.valueOf(entities.size()));
      insight.setConcurrentRuns(
          entities.stream()
              .filter(ae -> RunPhase.running.getPhase().equals(ae.getData().get("phase")))
              .count());
      insight.setTotalDuration(totalDuration);
      insight.setMedianDuration(entities.size() != 0 ? totalDuration / entities.size() : 0L);

      List<WorkflowRunSummary> summaries = new LinkedList<>();
      entities.forEach(
          e -> {
            WorkflowRunSummary summary = new WorkflowRunSummary();
            summary.setCreationDate(e.getCreationDate());
            summary.setDuration(Long.valueOf(e.getData().get("duration")));
            summary.setStatus(RunStatus.getRunStatus(e.getData().get("status")));
            summary.setWorkflowRef(e.getData().get("workflowRef"));
            Optional<AuditRecord> wfAE =
                auditQueryService.findFirstByScopeAndSelfRef(
                    AuditScope.WORKFLOW, e.getData().get("workflowRef"));
            if (wfAE.isPresent()) {
              summary.setWorkflowName(wfAE.get().getData().get("name"));
            }
            summaries.add(summary);
          });
      insight.setRuns(summaries);
    }
    return insight;
  }
}
