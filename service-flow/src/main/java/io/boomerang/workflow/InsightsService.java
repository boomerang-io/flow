package io.boomerang.workflow;

import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.model.WorkflowRunInsight;
import io.boomerang.common.model.WorkflowRunSummary;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.audit.AuditEntity;
import io.boomerang.core.audit.AuditRepository;
import io.boomerang.core.audit.AuditScope;
import io.boomerang.core.enums.RelationshipType;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

@Service
public class InsightsService {

  private static final Logger LOGGER = LogManager.getLogger();

  private final RelationshipService relationshipService;
  private final AuditRepository auditRepository;
  private final MongoTemplate mongoTemplate;

  public InsightsService(
      RelationshipService relationshipService,
      AuditRepository auditRepository,
      MongoTemplate mongoTemplate) {
    this.relationshipService = relationshipService;
    this.auditRepository = auditRepository;
    this.mongoTemplate = mongoTemplate;
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
      Optional<AuditEntity> teamAE =
          auditRepository.findFirstByScopeAndSelfName(AuditScope.TEAM, team);
      if (teamAE.isPresent()) {
        LOGGER.debug("Audit Team: {}", teamAE.toString());
        List<AuditEntity> workflowAEList =
            auditRepository.findByScopeAndParent(AuditScope.WORKFLOW, teamAE.get().getId());
        wfRefs = workflowAEList.stream().map(AuditEntity::getSelfRef).toList();
      }
    } else {
      wfRefs =
          relationshipService.filter(
              RelationshipType.WORKFLOW,
              workflowRefs,
              Optional.of(RelationshipType.TEAM),
              Optional.of(List.of(team)),
              false);
    }
    LOGGER.debug("Workflow Refs: {}", wfRefs.toString());
    if (!wfRefs.isEmpty()) {
      List<Criteria> criteriaList = new ArrayList<>();
      Criteria scopeCriteria = Criteria.where("scope").is("WORKFLOWRUN");
      criteriaList.add(scopeCriteria);

      Criteria dateCriteria = Criteria.where("creationDate").gte(from).lt(to);
      criteriaList.add(dateCriteria);

      Criteria wfCriteria = Criteria.where("data.workflowRef").in(wfRefs);
      criteriaList.add(wfCriteria);

      Criteria[] criteriaArray = criteriaList.toArray(new Criteria[criteriaList.size()]);
      Criteria allCriteria = new Criteria();
      if (criteriaArray.length > 0) {
        allCriteria.andOperator(criteriaArray);
      }
      Query query = new Query(allCriteria);
      LOGGER.debug("Query: " + query.toString());
      List<AuditEntity> entities = mongoTemplate.find(query, AuditEntity.class);
      LOGGER.debug("Entities: {}", entities.toString());

      // The following logic mirrors the Engine WorkflowRun Insight logic but is based on
      // AuditEntities for WorkfowRun Scope.
      // This ensures we include deleted Workflows and WorkflowRuns in our insights.

      // Collect the Stats
      Long totalDuration = 0L;
      Long duration;
      for (AuditEntity entity : entities) {
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
            Optional<AuditEntity> wfAE =
                auditRepository.findFirstByScopeAndSelfRef(
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
