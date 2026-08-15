package io.boomerang.core;

import io.boomerang.core.RelationshipService;
import io.boomerang.core.enums.RelationshipLabel;
import io.boomerang.core.enums.RelationshipType;
import io.boomerang.common.model.ChildWorkflowRunCreated;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Handles {@link ChildWorkflowRunCreated} events published by the engine's RunWorkflow task.
 * Replaces the former WorkflowClient -> InternalController {@code POST
 * /internal/workflow/{workflow}/run/{run}/relationship} HTTP callback: same Workspace lookup, same
 * node/edge write, now an in-process call.
 *
 * <p>{@code @EventListener} runs synchronously on the publisher's thread. The old HTTP handler
 * caught its own exceptions and returned a 500, which RestTemplate then turned back into a
 * thrown exception on the engine side - net effect was propagation, not swallowing. This listener
 * preserves that net effect directly: it does not catch, so a failure propagates out of
 * {@code publishEvent()} to the engine call site.
 */
@Component
public class RelationshipEventListener {

  private static final Logger LOGGER = LogManager.getLogger();

  private final RelationshipService relationshipService;

  public RelationshipEventListener(RelationshipService relationshipService) {
    this.relationshipService = relationshipService;
  }

  @EventListener
  public void onChildWorkflowRunCreated(ChildWorkflowRunCreated event) {
    String team =
        relationshipService.getParentByLabel(
            RelationshipLabel.HAS_WORKFLOW, RelationshipType.WORKFLOW, event.workflowRef());
    relationshipService.createNodeAndEdge(
        RelationshipType.WORKSPACE,
        team,
        RelationshipLabel.HAS_WORKFLOWRUN,
        RelationshipType.WORKFLOWRUN,
        event.workflowRunRef(),
        event.workflowRunRef(),
        Optional.empty(),
        Optional.empty());
    LOGGER.info(
        "Created relationship for team({}) and workflowrun({})", team, event.workflowRunRef());
  }
}
