package io.boomerang.core.audit;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.UnmeteredWorkflows;
import io.boomerang.core.entity.RelationshipNodeEntity;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.security.IdentityService;
import io.boomerang.common.model.WorkflowRunTransition;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Bridge from the winner-published WorkflowRun transition events to the audit trail: the run's
 * first status change records a CREATE event and reaching the completed phase records an UPDATE
 * event carrying the terminal facts (status, duration) and the run's cumulative duration, its task
 * runs' time from start to end added up ({@link CumulativeDuration}). These events outlive the run
 * documents and the Workflow itself, so the monthly quota figures read them rather than the live
 * collections — deleting a Workflow cannot reset them. A run of an unmetered workflow ({@link
 * UnmeteredWorkflows}) carries {@code unmetered: true} on both events and no cumulative duration,
 * so it uses none of the month.
 *
 * <p>TaskRun transitions are deliberately not audited: their volume would dwarf every other
 * event in the trail, and no consumer needs them (the quotas are WorkflowRun-scoped).
 *
 * <p>The actor is the current principal when the transition happens on a request thread (a
 * manual submit or cancel), else the system sentinel (watcher sweeps, schedule fires). Emission
 * is best-effort and never fails the transition.
 */
@Component
public class WorkflowRunAuditBridge {

  private static final Logger LOGGER = LogManager.getLogger();

  static final String RESOURCE_TYPE = "workflowrun";

  private final AuditEventEmitter emitter;
  private final IdentityService identityService;
  private final RelationshipService relationshipService;
  private final MongoTemplate mongoTemplate;
  private final UnmeteredWorkflows unmeteredWorkflows;

  public WorkflowRunAuditBridge(
      AuditEventEmitter emitter,
      IdentityService identityService,
      RelationshipService relationshipService,
      MongoTemplate mongoTemplate,
      UnmeteredWorkflows unmeteredWorkflows) {
    this.emitter = emitter;
    this.identityService = identityService;
    this.relationshipService = relationshipService;
    this.mongoTemplate = mongoTemplate;
    this.unmeteredWorkflows = unmeteredWorkflows;
  }

  @EventListener
  public void onWorkflowRunTransition(WorkflowRunTransition transition) {
    try {
      if (!emitter.captureEnabled(AuditLevel.WRITE)) {
        return;
      }
      // The first status change away from notstarted happens exactly once per run (admission,
      // or a direct cancel of a never-admitted run); so does reaching the completed phase (the
      // completion Compare-And-Set). A cancel straight out of notstarted is both at once.
      boolean created =
          RunStatus.notstarted == transition.fromStatus()
              && RunStatus.notstarted != transition.toStatus();
      boolean completed =
          RunPhase.completed == transition.toPhase()
              && RunPhase.completed != transition.fromPhase();
      if (!created && !completed) {
        return;
      }
      AuditActor actor = currentActorOrSystem();
      // The workflow's name and its owning workspace's name: one edge read and one node read.
      String workspaceRef = owningWorkspaceRef(transition);
      Map<String, RelationshipNodeEntity> nodes =
          names(Stream.of(transition.workflowRef(), workspaceRef).filter(Objects::nonNull).toList());
      String workflowName = slugOrNull(nodes, RelationshipType.WORKFLOW, transition.workflowRef());
      String workspace = slugOrNull(nodes, RelationshipType.WORKSPACE, workspaceRef);
      if (workspace == null) {
        LOGGER.warn(
            "[{}] No owning Workspace resolves for Workflow {} - auditing without one.",
            transition.id(),
            transition.workflowRef());
      }
      boolean unmetered = unmetered(transition, workspace, workflowName);
      if (created) {
        Map<String, Object> payload = payload(transition, workflowName, null);
        if (unmetered) {
          payload.put("unmetered", true);
        }
        emitter.emitAs(
            actor,
            AuditAction.CREATE,
            AuditLevel.WRITE,
            RESOURCE_TYPE,
            transition.id(),
            workflowName,
            workspace,
            payload);
      }
      if (completed) {
        // The completion Compare-And-Set wrote the duration before publishing this event.
        Query byId = Query.query(Criteria.where("_id").is(transition.id()));
        byId.fields().include("duration");
        WorkflowRunEntity run = mongoTemplate.findOne(byId, WorkflowRunEntity.class);
        Long duration = (run != null) ? run.getDuration() : 0L;
        Map<String, Object> payload = payload(transition, workflowName, duration);
        if (unmetered) {
          payload.put("unmetered", true);
        } else {
          // Tasks a cancel or timeout left running count up to now. A failed read still records
          // the run's end, without the figure.
          try {
            payload.put(
                "cumulativeDuration",
                CumulativeDuration.millis(taskRunTimes(transition.id()), Instant.now()));
          } catch (RuntimeException e) {
            LOGGER.warn(
                "[{}] No cumulative duration recorded for the run: {}",
                transition.id(),
                e.toString());
          }
        }
        emitter.emitAs(
            actor,
            AuditAction.UPDATE,
            AuditLevel.WRITE,
            RESOURCE_TYPE,
            transition.id(),
            workflowName,
            workspace,
            payload);
      }
    } catch (RuntimeException e) {
      LOGGER.warn(
          "Failed to audit WorkflowRun {} transition: {}", transition.id(), e.toString());
    }
  }

  /** The run facts the insights rollup reads back off the event. */
  private static Map<String, Object> payload(
      WorkflowRunTransition transition, String workflowName, Long duration) {
    Map<String, Object> payload = new HashMap<>();
    payload.put("workflowRef", transition.workflowRef());
    if (workflowName != null) {
      payload.put("workflowName", workflowName);
    }
    payload.put("status", transition.toStatus().getStatus());
    payload.put("phase", transition.toPhase().getPhase());
    if (duration != null) {
      payload.put("duration", duration);
    }
    return payload;
  }

  /**
   * Whether the run's workflow is unmetered. A failed read of the setting, the workflow or its
   * template counts the run as metered, so its events are still written and it still counts.
   */
  private boolean unmetered(
      WorkflowRunTransition transition, String workspace, String workflowName) {
    try {
      return unmeteredWorkflows.test(workspace, workflowName, transition.workflowRef());
    } catch (RuntimeException e) {
      LOGGER.warn(
          "[{}] Could not tell whether the run is unmetered; it is counted: {}",
          transition.id(),
          e.toString());
      return false;
    }
  }

  /**
   * The run's task runs, read for the fields the cumulative duration needs in one find on the
   * {@code run_tasks} index. A for-each parent's item array is never read: the projection only asks
   * whether the {@code foreach} setting is there.
   */
  private List<CumulativeDuration.TaskRunTime> taskRunTimes(String workflowRunRef) {
    Document projection =
        new Document("type", 1)
            .append("parentRef", 1)
            .append("reusedFromRef", 1)
            .append("phase", 1)
            .append("startTime", 1)
            .append("duration", 1)
            .append("statusReason", 1)
            .append("attempts", 1)
            .append(
                "foreachParent",
                new Document("$eq", List.of(new Document("$type", "$foreach"), "object")));
    List<CumulativeDuration.TaskRunTime> times = new ArrayList<>();
    for (Document document :
        mongoTemplate
            .getCollection(mongoTemplate.getCollectionName(TaskRunEntity.class))
            .find(new Document("workflowRunRef", workflowRunRef))
            .projection(projection)) {
      TaskRunEntity taskRun = mongoTemplate.getConverter().read(TaskRunEntity.class, document);
      times.add(
          new CumulativeDuration.TaskRunTime(
              taskRun.getType(),
              Boolean.TRUE.equals(document.getBoolean("foreachParent")),
              taskRun.getParentRef(),
              taskRun.getReusedFromRef(),
              taskRun.getPhase(),
              taskRun.getStartTime(),
              taskRun.getDuration(),
              taskRun.getStatusReason(),
              taskRun.getAttempts()));
    }
    return times;
  }

  private AuditActor currentActorOrSystem() {
    AuditActor actor = AuditActor.from(identityService.getCurrentIdentity());
    return (actor != null) ? actor : AuditActor.system();
  }

  /**
   * The owning Workspace's ref, from the Workflow's parent edge, the way the Action rollup reads
   * it; null when none resolves (an already-deleted Workflow, a Workflow no workspace owns), which
   * audits with no workspace rather than failing the transition.
   */
  private String owningWorkspaceRef(WorkflowRunTransition transition) {
    try {
      String parentRef =
          relationshipService.getParentByLabel(
              RelationshipLabel.HAS_WORKFLOW, RelationshipType.WORKFLOW, transition.workflowRef());
      return (parentRef != null && !parentRef.isBlank()) ? parentRef : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  // The workflow and workspace nodes with these refs, keyed by ref; none when the read fails.
  private Map<String, RelationshipNodeEntity> names(List<String> refs) {
    try {
      return refs.isEmpty()
          ? Map.of()
          : relationshipService.nodesByRef(refs, RelationshipType.WORKFLOW, RelationshipType.WORKSPACE);
    } catch (RuntimeException e) {
      return Map.of();
    }
  }

  // The slug of the node of this type and ref among those read, or null.
  private static String slugOrNull(
      Map<String, RelationshipNodeEntity> nodes, RelationshipType type, String ref) {
    RelationshipNodeEntity node = (ref != null) ? nodes.get(ref) : null;
    return (node != null && type.getLabel().equals(node.getType())) ? node.getSlug() : null;
  }
}
