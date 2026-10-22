package io.boomerang.workflow;

import io.boomerang.client.EngineClient;
import io.boomerang.common.entity.ActionEntity;
import io.boomerang.common.enums.ActionStatus;
import io.boomerang.common.enums.ActionType;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.model.Actioner;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.TaskRunEndRequest;
import io.boomerang.common.model.Workflow;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.UserService;
import io.boomerang.core.entity.UserEntity;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.model.User;
import io.boomerang.error.BoomerangError;
import io.boomerang.error.BoomerangException;
import io.boomerang.workflow.entity.ApproverGroupEntity;
import io.boomerang.workflow.model.Action;
import io.boomerang.workflow.model.ActionRequest;
import io.boomerang.workflow.model.ActionSummary;
import io.boomerang.workflow.repository.ActionRepository;
import io.boomerang.workflow.repository.ApproverGroupRepository;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Service;

@Service
public class ActionService {

  private static final Logger LOGGER = LogManager.getLogger();

  private final ActionRepository actionRepository;
  private final ApproverGroupRepository approverGroupRepository;
  private final EngineClient engineClient;
  private final RelationshipService relationshipService;
  private final UserService userService;
  private final MongoTemplate mongoTemplate;

  public ActionService(
      ActionRepository actionRepository,
      ApproverGroupRepository approverGroupRepository,
      EngineClient engineClient,
      RelationshipService relationshipService,
      UserService userService,
      MongoTemplate mongoTemplate) {
    this.actionRepository = actionRepository;
    this.approverGroupRepository = approverGroupRepository;
    this.engineClient = engineClient;
    this.relationshipService = relationshipService;
    this.userService = userService;
    this.mongoTemplate = mongoTemplate;
  }

  /*
   * Updates / Processes an Action
   *
   * TODO: at this point in time, only users can process Actions even though we have an API that
   * allows it. Once fixed will need to adjust the token scope on the Controller
   */
  public void action(String team, List<ActionRequest> requests) {
    for (ActionRequest request : requests) {
      Optional<ActionEntity> optActionEntity = this.actionRepository.findById(request.getId());
      if (!optActionEntity.isPresent()) {
        throw new BoomerangException(BoomerangError.ACTION_INVALID_REF);
      }

      ActionEntity actionEntity = optActionEntity.get();
      if (actionEntity.getActioners() == null) {
        actionEntity.setActioners(new LinkedList<>());
      }

      // Check if requester has access to the Workflow the Action Entity belongs to
      if (!relationshipService.check(
          RelationshipType.WORKFLOW,
          actionEntity.getWorkflowRef(),
          Optional.empty(),
          Optional.empty())) {
        throw new BoomerangException(BoomerangError.ACTION_INVALID_REF);
      }

      boolean canBeActioned = false;
      UserEntity userEntity = userService.getCurrentUser();
      if (actionEntity.getType() == ActionType.manual) {
        // Manual tasks only require a single yes or no
        canBeActioned = true;
      } else if (actionEntity.getType() == ActionType.approval) {
        if (actionEntity.getApproverGroupRef() != null) {
          List<String> approverGroupRefs =
              relationshipService.filter(
                  RelationshipType.APPROVERGROUP,
                  Optional.of(List.of(actionEntity.getApproverGroupRef())),
                  Optional.of(RelationshipType.TEAM),
                  Optional.of(List.of(team)));
          if (approverGroupRefs.isEmpty()) {
            throw new BoomerangException(BoomerangError.ACTION_INVALID_APPROVERGROUP);
          }
          Optional<ApproverGroupEntity> approverGroupEntity =
              approverGroupRepository.findById(actionEntity.getApproverGroupRef());
          if (approverGroupEntity.isEmpty()) {
            throw new BoomerangException(BoomerangError.ACTION_INVALID_APPROVERGROUP);
          }
          boolean partOfGroup =
              approverGroupEntity.get().getApprovers().contains(userEntity.getId());
          if (partOfGroup) {
            canBeActioned = true;
          }
        } else {
          canBeActioned = true;
        }
      }

      if (canBeActioned) {
        Actioner actioner = new Actioner();
        actioner.setDate(new Date());
        actioner.setApproverId(userEntity.getId());
        actioner.setComments(request.getComments());
        actioner.setApproved(request.isApproved());
        actionEntity.getActioners().add(actioner);
      }

      int numberApprovals = actionEntity.getNumberOfApprovers();
      long approvedCount = actionEntity.getActioners().stream().filter(x -> x.isApproved()).count();
      long numberOfActioners = actionEntity.getActioners().size();

      if (numberOfActioners >= numberApprovals) {
        boolean approved = false;
        if (approvedCount == numberApprovals) {
          approved = true;
        }
        actionEntity.setStatus(approved ? ActionStatus.approved : ActionStatus.rejected);
        try {
          TaskRunEndRequest endRequest = new TaskRunEndRequest();
          endRequest.setStatus(approved ? RunStatus.succeeded : RunStatus.failed);
          engineClient.endTaskRun(actionEntity.getTaskRunRef(), endRequest);
        } catch (BoomerangException e) {
          throw new BoomerangException(BoomerangError.ACTION_UNABLE_TO_ACTION);
        }
        this.actionRepository.save(actionEntity);
      }
    }
  }

  private Action convertToAction(ActionEntity actionEntity) {
    Action action = new Action(actionEntity);

    action.setApprovalsRequired(actionEntity.getNumberOfApprovers());

    if (actionEntity.getActioners() != null) {
      long aprovalCount = actionEntity.getActioners().stream().filter(x -> x.isApproved()).count();

      action.setNumberOfApprovals(aprovalCount);
      for (Actioner audit : actionEntity.getActioners()) {
        Optional<User> user = userService.getUserByID(audit.getApproverId());
        if (user.isPresent()) {
          audit.setApproverName(user.get().getName());
          audit.setApproverEmail(user.get().getEmail());
        }
      }
      action.setActioners(actionEntity.getActioners());
    }

    Workflow workflow =
        engineClient.getWorkflow(actionEntity.getWorkflowRef(), Optional.empty(), false);
    action.setWorkflowName(workflow.getName());
    try {
      TaskRun taskRun = engineClient.getTaskRun(actionEntity.getTaskRunRef());
      action.setTaskName(taskRun.getName());
    } catch (BoomerangException e) {
      LOGGER.error(
          "convertToAction() - Skipping specific TaskRun as not available. Most likely bad data");
    }

    return action;
  }

  public Action get(String team, String id) {
    Optional<ActionEntity> actionEntity = this.actionRepository.findById(id);
    if (actionEntity.isEmpty()) {
      throw new BoomerangException(BoomerangError.ACTION_INVALID_REF);
    }
    return this.convertToAction(actionEntity.get());
  }

  public Page<Action> query(
      String team,
      Optional<Date> from,
      Optional<Date> to,
      Pageable pageable,
      Optional<List<ActionType>> queryTypes,
      Optional<List<ActionStatus>> queryStatus,
      Optional<List<String>> queryWorkflows) {

    // Get Refs that request has access to
    List<String> workflowRefs =
        relationshipService.filter(
            RelationshipType.WORKFLOW,
            queryWorkflows,
            Optional.of(RelationshipType.TEAM),
            Optional.of(List.of(team)),
            false);
    if (workflowRefs == null || workflowRefs.size() == 0) {
      return Page.empty();
    }

    Criteria criteria =
        buildCriteriaList(from, to, Optional.of(workflowRefs), queryTypes, queryStatus);
    Query query = new Query(criteria).with(pageable);

    List<ActionEntity> actionEntities =
        mongoTemplate.find(query.with(pageable), ActionEntity.class);

    List<Action> actions = new LinkedList<>();
    actionEntities.forEach(
        a -> {
          actions.add(this.convertToAction(a));
        });

    Page<Action> pages =
        PageableExecutionUtils.getPage(
            actions, pageable, () -> mongoTemplate.count(query, ActionEntity.class));

    return pages;
  }

  public ActionSummary summary(
      String team,
      Optional<Date> fromDate,
      Optional<Date> toDate,
      Optional<List<String>> queryWorkflows) {
    ActionSummary summary = new ActionSummary();
    List<String> workflowRefs =
        relationshipService.filter(
            RelationshipType.WORKFLOW,
            queryWorkflows,
            Optional.of(RelationshipType.TEAM),
            Optional.of(List.of(team)),
            false);
    if (workflowRefs == null || workflowRefs.size() == 0) {
      return summary;
    }
    long approvalCount =
        this.getActionCountForType(
            ActionType.approval, fromDate, toDate, Optional.of(workflowRefs));
    long manualCount =
        this.getActionCountForType(ActionType.manual, fromDate, toDate, Optional.of(workflowRefs));
    long rejectedCount = getActionCountForStatus(ActionStatus.rejected, fromDate, toDate);
    long approvedCount = getActionCountForStatus(ActionStatus.approved, fromDate, toDate);
    long submittedCount = getActionCountForStatus(ActionStatus.submitted, fromDate, toDate);
    long total = rejectedCount + approvedCount + submittedCount;
    long approvalRateCount = 0;

    if (total != 0) {
      approvalRateCount = (((approvedCount + rejectedCount) / total) * 100);
    }

    summary.setApprovalsRate(approvalRateCount);
    summary.setManual(manualCount);
    summary.setApprovals(approvalCount);
    return summary;
  }

  private long getActionCountForType(
      ActionType type,
      Optional<Date> from,
      Optional<Date> to,
      Optional<List<String>> workflowRefs) {
    Criteria criteria =
        this.buildCriteriaList(
            from,
            to,
            workflowRefs,
            Optional.of(List.of(type)),
            Optional.of(List.of(ActionStatus.submitted)));
    return mongoTemplate.count(new Query(criteria), ActionEntity.class);
  }

  private long getActionCountForStatus(
      ActionStatus status, Optional<Date> from, Optional<Date> to) {
    Criteria criteria =
        this.buildCriteriaList(
            from, to, Optional.empty(), Optional.empty(), Optional.of(List.of(status)));
    return mongoTemplate.count(new Query(criteria), ActionEntity.class);
  }

  private Criteria buildCriteriaList(
      Optional<Date> from,
      Optional<Date> to,
      Optional<List<String>> workflowRefs,
      Optional<List<ActionType>> type,
      Optional<List<ActionStatus>> status) {
    List<Criteria> criterias = new ArrayList<>();

    if (from.isPresent()) {
      Criteria dynamicCriteria = Criteria.where("creationDate").gte(from.get());
      criterias.add(dynamicCriteria);
    }

    if (to.isPresent()) {
      Criteria dynamicCriteria = Criteria.where("creationDate").lte(to.get());
      criterias.add(dynamicCriteria);
    }

    if (workflowRefs.isPresent()) {
      Criteria workflowIdsCriteria = Criteria.where("workflowRef").in(workflowRefs.get());
      criterias.add(workflowIdsCriteria);
    }

    if (type.isPresent()) {
      Criteria dynamicCriteria = Criteria.where("type").in(type.get());
      criterias.add(dynamicCriteria);
    }

    if (status.isPresent()) {
      Criteria dynamicCriteria = Criteria.where("status").in(status.get());
      criterias.add(dynamicCriteria);
    }

    return new Criteria().andOperator(criterias.toArray(new Criteria[criterias.size()]));
  }

  public void deleteAllByWorkflow(String workflowRef) {
    actionRepository.deleteByWorkflowRef(workflowRef);
  }

  public void cancelAllByWorkflowRun(String workflowRunRef) {
    actionRepository.updateStatusByWorkflowRunRef(workflowRunRef, ActionStatus.cancelled);
  }
}
