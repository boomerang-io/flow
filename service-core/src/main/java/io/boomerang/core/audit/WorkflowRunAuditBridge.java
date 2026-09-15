package io.boomerang.core.audit;

import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.core.RelationshipService;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.core.security.IdentityService;
import io.boomerang.engine.model.WorkflowRunTransition;
import io.boomerang.engine.repository.WorkflowRunRepository;
import java.util.HashMap;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Bridge from the winner-published WorkflowRun transition events to the audit trail: the run's
 * first status change records a CREATE event and reaching the completed phase records an UPDATE
 * event carrying the terminal facts (status, duration). These events outlive the run documents
 * and the Workflow itself, so the monthly run quota counter and the workspace insights read them
 * rather than the live collections — deleting a Workflow cannot reset either.
 *
 * <p>TaskRun transitions are deliberately not audited: their volume would dwarf every other
 * event in the trail, and no consumer needs them (quotas and insights are WorkflowRun-scoped).
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
  private final WorkflowRunRepository workflowRunRepository;

  public WorkflowRunAuditBridge(
      AuditEventEmitter emitter,
      IdentityService identityService,
      RelationshipService relationshipService,
      WorkflowRunRepository workflowRunRepository) {
    this.emitter = emitter;
    this.identityService = identityService;
    this.relationshipService = relationshipService;
    this.workflowRunRepository = workflowRunRepository;
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
      String workflowName = slugOrNull(RelationshipType.WORKFLOW, transition.workflowRef());
      String workspace = owningWorkspace(transition);
      if (created) {
        emitter.emitAs(
            actor,
            AuditAction.CREATE,
            AuditLevel.WRITE,
            RESOURCE_TYPE,
            transition.id(),
            workflowName,
            workspace,
            payload(transition, workflowName, null));
      }
      if (completed) {
        // The completion Compare-And-Set wrote the duration before publishing this event.
        Long duration =
            workflowRunRepository
                .findById(transition.id())
                .map(WorkflowRunEntity::getDuration)
                .orElse(0L);
        emitter.emitAs(
            actor,
            AuditAction.UPDATE,
            AuditLevel.WRITE,
            RESOURCE_TYPE,
            transition.id(),
            workflowName,
            workspace,
            payload(transition, workflowName, duration));
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

  private AuditActor currentActorOrSystem() {
    AuditActor actor = AuditActor.from(identityService.getCurrentIdentity());
    return (actor != null) ? actor : AuditActor.system();
  }

  /**
   * Resolve the owning Workspace's name from the Workflow's parent edge, the way the Action
   * rollup does. An unresolvable owner (already-deleted Workflow, engine mode without a graph)
   * audits with none rather than failing the transition.
   */
  private String owningWorkspace(WorkflowRunTransition transition) {
    try {
      String parentRef =
          relationshipService.getParentByLabel(
              RelationshipLabel.HAS_WORKFLOW, RelationshipType.WORKFLOW, transition.workflowRef());
      if (parentRef != null && !parentRef.isBlank()) {
        return relationshipService.getSlugByRefForType(RelationshipType.WORKSPACE, parentRef);
      }
    } catch (RuntimeException e) {
      // Fall through to the unresolved log below.
    }
    LOGGER.warn(
        "[{}] No owning Workspace resolves for Workflow {} - auditing without one.",
        transition.id(),
        transition.workflowRef());
    return null;
  }

  private String slugOrNull(RelationshipType type, String ref) {
    try {
      return relationshipService.getSlugByRefForType(type, ref);
    } catch (RuntimeException e) {
      return null;
    }
  }
}
