package io.boomerang.workflow;

import io.boomerang.client.EngineClient;
import io.boomerang.client.WorkflowRunResponsePage;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.error.BoomerangError;
import io.boomerang.error.BoomerangException;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.common.model.WorkflowRunCount;
import io.boomerang.common.model.WorkflowRunInsight;
import io.boomerang.common.model.WorkflowRunRequest;
import java.util.List;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/*
 * This service replicates the required calls for Engine WorkflowRunV1 APIs
 *
 * It will
 * - Check authorization using Relationships
 * - Forward call onto Engine
 */
@Service
public class WorkflowRunService {

  private static final Logger LOGGER = LogManager.getLogger();

  private final EngineClient engineClient;
  private final RelationshipService relationshipService;
  private final ActionService actionService;

  public WorkflowRunService(
      EngineClient engineClient,
      RelationshipService relationshipService,
      ActionService actionService) {
    this.engineClient = engineClient;
    this.relationshipService = relationshipService;
    this.actionService = actionService;
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
    if (relationshipService.check(
        RelationshipType.WORKFLOWRUN,
        workflowRunId,
        Optional.of(RelationshipType.TEAM),
        Optional.of(List.of(team)))) {
      WorkflowRun wfRun = engineClient.getWorkflowRun(workflowRunId, withTasks);
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
        relationshipService.filter(
            RelationshipType.WORKFLOW,
            queryWorkflows,
            Optional.of(RelationshipType.TEAM),
            Optional.of(List.of(queryTeam)),
            false);
    // TODO query workflow runs
    LOGGER.debug("Workflow Refs: {}", wfRefs.toString());
    if (!wfRefs.isEmpty()) {
      return engineClient.queryWorkflowRuns(
          fromDate,
          toDate,
          queryLimit,
          queryPage,
          queryOrder,
          queryLabels,
          queryStatus,
          queryPhase,
          Optional.empty(),
          Optional.of(wfRefs),
          queryTriggers);
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
        relationshipService.filter(
            RelationshipType.WORKFLOW,
            queryWorkflows,
            Optional.of(RelationshipType.TEAM),
            Optional.of(List.of(queryTeam)),
            false);
    LOGGER.debug("Workflow Refs: {}", wfRefs.toString());

    return engineClient.insightWorkflowRuns(
        queryLabels, Optional.empty(), Optional.of(wfRefs), from, to);
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
        relationshipService.filter(
            RelationshipType.WORKFLOW,
            queryWorkflows,
            Optional.of(RelationshipType.TEAM),
            Optional.of(List.of(queryTeam)),
            false);
    LOGGER.debug("Workflow Refs: {}", wfRefs.toString());

    return engineClient.countWorkflowRuns(queryLabels, Optional.of(wfRefs), from, to);
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
    if (relationshipService.check(
        RelationshipType.WORKFLOWRUN,
        workflowRunId,
        Optional.of(RelationshipType.TEAM),
        Optional.of(List.of(team)))) {
      WorkflowRun wfRun = engineClient.startWorkflowRun(workflowRunId, optRunRequest);
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
    if (relationshipService.check(
        RelationshipType.WORKFLOWRUN,
        workflowRunId,
        Optional.of(RelationshipType.TEAM),
        Optional.of(List.of(team)))) {
      WorkflowRun wfRun = engineClient.finalizeWorkflowRun(workflowRunId);
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
    if (relationshipService.check(
        RelationshipType.WORKFLOWRUN,
        workflowRunId,
        Optional.of(RelationshipType.TEAM),
        Optional.of(List.of(team)))) {
      WorkflowRun wfRun = engineClient.cancelWorkflowRun(workflowRunId);
      actionService.cancelAllByWorkflowRun(workflowRunId);
      return ResponseEntity.ok(wfRun);
    } else {
      // TODO: do we want to return invalid ref or unauthorized
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
    if (relationshipService.check(
        RelationshipType.WORKFLOWRUN,
        workflowRunId,
        Optional.of(RelationshipType.TEAM),
        Optional.of(List.of(team)))) {
      WorkflowRun wfRun = engineClient.retryWorkflowRun(workflowRunId);

      // Creates relationship with owning team
      relationshipService.createNodeAndEdge(
          RelationshipType.TEAM,
          team,
          RelationshipLabel.HAS_WORKFLOWRUN,
          RelationshipType.WORKFLOWRUN,
          wfRun.getId(),
          wfRun.getId(),
          Optional.empty(),
          Optional.empty());
      return ResponseEntity.ok(wfRun);
    } else {
      throw new BoomerangException(BoomerangError.WORKFLOWRUN_INVALID_REF);
    }
  }
}
