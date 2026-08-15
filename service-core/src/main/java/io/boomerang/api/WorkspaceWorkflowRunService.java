package io.boomerang.api;

import io.boomerang.api.model.WorkflowRunResponsePage;
import io.boomerang.core.RunScopeResolver;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowRunCount;
import io.boomerang.common.model.WorkflowRunInsight;
import io.boomerang.common.model.WorkflowRunRequest;
import io.boomerang.engine.WorkflowRunService;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/*
 * This service replicates the required calls for Engine WorkflowRunV1 APIs
 *
 * It will
 * - Check authorization using Relationships (via RunScopeResolver)
 * - Forward call onto Engine
 */
@Service
public class WorkspaceWorkflowRunService {

  private static final Logger LOGGER = LogManager.getLogger();

  private final WorkflowRunService engineWorkflowRunService;
  private final RunScopeResolver runScopeResolver;
  private final WorkspaceActionService workspaceActionService;

  public WorkspaceWorkflowRunService(
      WorkflowRunService engineWorkflowRunService,
      RunScopeResolver runScopeResolver,
      WorkspaceActionService workspaceActionService) {
    this.engineWorkflowRunService = engineWorkflowRunService;
    this.runScopeResolver = runScopeResolver;
    this.workspaceActionService = workspaceActionService;
  }

  /*
   * Get Workflow Run
   *
   * No need to validate params as they are either defaulted or optional
   */
  public ResponseEntity<WorkflowRun> get(String team, String workflowRunId, boolean withTasks) {
    if (workflowRunId == null || workflowRunId.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF);
    }
    if (runScopeResolver.checkInScope(RelationshipType.WORKFLOWRUN, workflowRunId, team)) {
      WorkflowRun wfRun = engineWorkflowRunService.get(workflowRunId, withTasks);
      return ResponseEntity.ok(wfRun);
    } else {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF);
    }
  }

  /*
   * Query for WorkflowRun
   *
   * No need to validate params as they are either defaulted or optional
   */
  public WorkflowRunResponsePage query(
      String queryTeam,
      Optional<Long> fromDate,
      Optional<Long> toDate,
      Optional<Integer> queryLimit,
      Optional<Integer> queryPage,
      Optional<Direction> queryOrder,
      Optional<List<String>> queryLabels,
      Optional<List<String>> queryStatus,
      Optional<List<String>> queryPhase,
      Optional<List<String>> queryWorkflowRuns,
      Optional<List<String>> queryWorkflows,
      Optional<List<String>> queryTriggers) {

    List<String> wfRefs =
        runScopeResolver.filterInScope(RelationshipType.WORKFLOW, queryWorkflows, queryTeam, false);
    // TODO query workflow runs
    LOGGER.debug("Workflow Refs: {}", wfRefs.toString());
    if (!wfRefs.isEmpty()) {
      Page<WorkflowRun> page =
          engineWorkflowRunService.query(
              fromDate.map(Date::new),
              toDate.map(Date::new),
              queryLimit,
              queryPage,
              queryOrder,
              queryLabels,
              queryStatus,
              queryPhase,
              Optional.empty(),
              Optional.of(wfRefs),
              queryTriggers);
      return new WorkflowRunResponsePage(
          page.getContent(), page.getPageable(), page.getTotalElements());
    } else {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF);
    }
  }

  /*
   * Retrieve the insights / statistics for a specific period of time and filters
   */
  public WorkflowRunInsight insight(
      String queryTeam,
      Optional<Long> from,
      Optional<Long> to,
      Optional<List<String>> queryLabels,
      Optional<List<String>> queryWorkflows) {
    // Check the queryWorkflows
    List<String> wfRefs =
        runScopeResolver.filterInScope(RelationshipType.WORKFLOW, queryWorkflows, queryTeam, false);
    LOGGER.debug("Workflow Refs: {}", wfRefs.toString());

    return engineWorkflowRunService.insights(
        from.map(Date::new), to.map(Date::new), queryLabels, Optional.empty(), Optional.of(wfRefs));
  }

  /*
   * Retrieve the insights / statistics for a specific period of time and filters
   */
  public WorkflowRunCount count(
      String queryTeam,
      Optional<Long> from,
      Optional<Long> to,
      Optional<List<String>> queryLabels,
      Optional<List<String>> queryWorkflows) {
    List<String> wfRefs =
        runScopeResolver.filterInScope(RelationshipType.WORKFLOW, queryWorkflows, queryTeam, false);
    LOGGER.debug("Workflow Refs: {}", wfRefs.toString());

    return engineWorkflowRunService.count(
        from.map(Date::new), to.map(Date::new), queryLabels, Optional.of(wfRefs));
  }

  /*
   * Start WorkflowRun
   *
   * TODO: do we expose this one?
   */
  public ResponseEntity<WorkflowRun> start(
      String team, String workflowRunId, Optional<WorkflowRunRequest> optRunRequest) {
    if (workflowRunId == null || workflowRunId.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF);
    }
    if (runScopeResolver.checkInScope(RelationshipType.WORKFLOWRUN, workflowRunId, team)) {
      WorkflowRun wfRun = engineWorkflowRunService.start(workflowRunId, optRunRequest);
      return ResponseEntity.ok(wfRun);
    } else {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF);
    }
  }

  /*
   * Finalize WorkflowRun
   *
   * TODO: do we expose this one?
   */
  public ResponseEntity<WorkflowRun> finalize(String team, String workflowRunId) {
    if (workflowRunId == null || workflowRunId.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF);
    }
    if (runScopeResolver.checkInScope(RelationshipType.WORKFLOWRUN, workflowRunId, team)) {
      WorkflowRun wfRun = engineWorkflowRunService.finalize(workflowRunId);
      return ResponseEntity.ok(wfRun);
    } else {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF);
    }
  }

  /*
   * Cancel WorkflowRun
   */
  public ResponseEntity<WorkflowRun> cancel(String team, String workflowRunId) {
    if (workflowRunId == null || workflowRunId.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF);
    }
    if (runScopeResolver.checkInScope(RelationshipType.WORKFLOWRUN, workflowRunId, team)) {
      WorkflowRun wfRun = engineWorkflowRunService.cancel(workflowRunId);
      workspaceActionService.cancelAllByWorkflowRun(workflowRunId);
      return ResponseEntity.ok(wfRun);
    } else {
      // TODO: do we want to return invalid ref or unauthorized
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF);
    }
  }

  /*
   * Pause WorkflowRun
   */
  public ResponseEntity<WorkflowRun> pause(String team, String workflowRunId) {
    if (workflowRunId == null || workflowRunId.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF);
    }
    if (runScopeResolver.checkInScope(RelationshipType.WORKFLOWRUN, workflowRunId, team)) {
      WorkflowRun wfRun = engineWorkflowRunService.pause(workflowRunId);
      return ResponseEntity.ok(wfRun);
    } else {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF);
    }
  }

  /*
   * Resume WorkflowRun
   */
  public ResponseEntity<WorkflowRun> resume(String team, String workflowRunId) {
    if (workflowRunId == null || workflowRunId.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF);
    }
    if (runScopeResolver.checkInScope(RelationshipType.WORKFLOWRUN, workflowRunId, team)) {
      WorkflowRun wfRun = engineWorkflowRunService.resume(workflowRunId);
      return ResponseEntity.ok(wfRun);
    } else {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF);
    }
  }

  /*
   * Retry WorkflowRun
   */
  public ResponseEntity<WorkflowRun> retry(String team, String workflowRunId) {
    if (workflowRunId == null || workflowRunId.isBlank()) {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF);
    }
    if (runScopeResolver.checkInScope(RelationshipType.WORKFLOWRUN, workflowRunId, team)) {
      WorkflowRun wfRun = engineWorkflowRunService.retry(workflowRunId, false, 1);

      // Creates relationship with owning team
      runScopeResolver.linkToScope(
          team,
          RelationshipLabel.HAS_WORKFLOWRUN,
          RelationshipType.WORKFLOWRUN,
          wfRun.getId(),
          wfRun.getId());
      return ResponseEntity.ok(wfRun);
    } else {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF);
    }
  }
}
