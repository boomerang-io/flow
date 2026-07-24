package io.boomerang.engine;

import static io.boomerang.util.ConvertUtil.entityToModel;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.AgentRegistrationRequest;
import io.boomerang.common.model.TaskRun;
import io.boomerang.common.model.WorkflowRun;
import io.boomerang.engine.entity.AgentEntity;
import io.boomerang.engine.repository.AgentRepository;
import io.boomerang.engine.repository.TaskRunRepository;
import io.boomerang.engine.repository.WorkflowRunRepository;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

@Service
public class AgentService {
  private static final Logger LOGGER = LogManager.getLogger();

  private static final Integer MAX_POLL_INTERVAL = 30000;
  private static final Integer MAX_SLEEP_INTERVAL = 1000; // 1 sec
  private static final int PAGE_SIZE = 20;

  // Kill switch: stops CLAIMING only. The watcher's recovery sweeps are never gated by it.
  @Value("${flow.queue.enabled:true}")
  private boolean queueEnabled;

  private final AgentRepository agentRepository;
  private final WorkflowRunRepository wfRunRepository;
  private final TaskRunRepository taskRunRepository;

  public AgentService(
      AgentRepository agentRepository,
      WorkflowRunRepository wfRunRepository,
      TaskRunRepository taskRunRepository) {
    this.agentRepository = agentRepository;
    this.wfRunRepository = wfRunRepository;
    this.taskRunRepository = taskRunRepository;
  }

  /**
   * Registers the Agent
   *
   * <p>This method saves the agent's details to the database. This can be used in the future to
   * remove an agent and its token that we no longer trust
   *
   * <p>TODO: expand capabilities to allow revoking an agent's registration and also storing the
   * token that was used.
   *
   * @param request
   * @return the ID of the registered agent
   */
  public String register(AgentRegistrationRequest request) {
    if (request == null || request.getHost() == null || request.getHost().isEmpty()) {
      throw new IllegalArgumentException("Agent ID must not be null or empty");
    }

    AgentEntity entity =
        agentRepository.save(
            new AgentEntity(
                request.getName(),
                request.getHost(),
                TaskType.convertToTaskTypeList(request.getTaskTypes()),
                request.getVersion()));

    // Log the registration for debugging purposes
    LOGGER.debug(
        "Registered agent: {}({}) with task types: {}",
        entity.getId(),
        entity.getName(),
        request.getTaskTypes());

    return entity.getId();
  }

  /**
   * Long-poll endpoint dispatching WorkflowRuns to the agent.
   *
   * <p>Each cycle pages the eligible candidates (provision and workspace teardown) and claims
   * each one individually via a Compare-And-Set; racing agents cannot both win a run and the
   * response contains only the documents this agent actually claimed. Claimed and terminal runs
   * are not redelivered.
   *
   * @param agentId
   * @return
   */
  public ResponseEntity<List<WorkflowRun>> getWorkflowQueue(String agentId) {
    if (!queueEnabled) {
      LOGGER.warn("Queue claiming disabled (flow.queue.enabled=false). Returning no content.");
      return ResponseEntity.noContent().build();
    }
    // Validate the Agent
    if (!agentRepository.existsById(agentId)) {
      LOGGER.error("Agent {} not registered", agentId);
      throw new IllegalArgumentException("Agent ID does not exist or is not registered.");
    }
    agentRepository.updateLastConnected(agentId, new Date());
    // TODO add in future filtering of workflows based on labels or a setting

    // Long poll logic
    Instant endTime = Instant.now().plusMillis(MAX_POLL_INTERVAL); // Keep connection open
    LOGGER.debug("Starting long poll queue for agent: {}", agentId);
    while (Instant.now().isBefore(endTime)) {
      LOGGER.debug("Checking queue for agent: {}", agentId);
      try {
        // The claimed pre-images carry the wire shape the agent acts on: pending/ready to
        // provision and start, completed to tear down and finalize.
        List<WorkflowRun> workflowRuns = new LinkedList<>();
        for (WorkflowRunEntity candidate : wfRunRepository.findClaimableForProvision(PAGE_SIZE)) {
          WorkflowRunEntity claimed =
              wfRunRepository.tryClaimForProvision(candidate.getId(), agentId);
          if (claimed != null) {
            workflowRuns.add(entityToModel(claimed, WorkflowRun.class));
          }
        }
        for (WorkflowRunEntity candidate : wfRunRepository.findClaimableForTeardown(PAGE_SIZE)) {
          WorkflowRunEntity claimed =
              wfRunRepository.tryClaimForTeardown(candidate.getId(), agentId);
          if (claimed != null) {
            workflowRuns.add(entityToModel(claimed, WorkflowRun.class));
          }
        }

        LOGGER.debug("Claimed {} WorkflowRuns for Agent: {}", workflowRuns.size(), agentId);
        if (!workflowRuns.isEmpty()) {
          return ResponseEntity.ok(workflowRuns);
        }
        // Sleep for a short interval before checking again
        Thread.sleep(MAX_SLEEP_INTERVAL);
      } catch (Exception e) {
        LOGGER.error("Error retrieving workflows for agent {}: {}", agentId, e.getMessage());
      }
    }
    LOGGER.debug("Ending long poll queue for agent: {}", agentId);
    return ResponseEntity.noContent().build();
  }

  /**
   * Long-poll endpoint dispatching TaskRuns to the agent.
   *
   * <p>Each cycle pages the eligible candidates (ready, pending, unclaimed, of the agent's task
   * types) and claims each one individually via a Compare-And-Set; racing agents cannot both win
   * a TaskRun and the response contains only the documents this agent actually claimed. Terminal
   * runs are never redelivered.
   *
   * @param agentId
   * @return
   */
  public ResponseEntity<List<TaskRun>> getTaskQueue(String agentId) {
    if (!queueEnabled) {
      LOGGER.warn("Queue claiming disabled (flow.queue.enabled=false). Returning no content.");
      return ResponseEntity.noContent().build();
    }
    // Validate the Agent
    if (!agentRepository.existsById(agentId)) {
      LOGGER.error("Agent {} not registered", agentId);
      throw new IllegalArgumentException("Agent ID does not exist or is not registered.");
    }
    agentRepository.updateLastConnected(agentId, new Date());
    AgentEntity entity = agentRepository.findTaskTypesByAgentId(agentId);
    if (entity != null && entity.getTaskTypes() != null && entity.getTaskTypes().isEmpty()) {
      LOGGER.warn("Agent {} has no task types defined. Returning 204.", agentId);
      return ResponseEntity.noContent().build();
    }

    LOGGER.debug("Entity: {}", entity);

    // Long poll logic
    Instant endTime =
        Instant.now().plusMillis(MAX_POLL_INTERVAL); // Keep connection open for 30 seconds
    LOGGER.debug("Starting long poll queue for agent: {}", agentId);
    while (Instant.now().isBefore(endTime)) {
      LOGGER.debug(
          "Checking queue for agent: {} with task types: {}", agentId, entity.getTaskTypes());
      try {
        // Page then claim: the Compare-And-Set re-checks eligibility per document, so a
        // candidate another agent claimed between page and claim is simply skipped. The
        // returned pre-images carry the pending/ready wire shape the agent executes.
        List<TaskRun> taskRuns = new LinkedList<>();
        for (TaskRunEntity candidate :
            taskRunRepository.findClaimable(entity.getTaskTypes(), PAGE_SIZE)) {
          TaskRunEntity claimed = taskRunRepository.tryClaim(candidate.getId(), agentId);
          if (claimed != null) {
            taskRuns.add(new TaskRun(claimed));
          }
        }

        LOGGER.debug("Claimed {} TaskRuns for Agent: {}", taskRuns.size(), agentId);
        if (!taskRuns.isEmpty()) {
          return ResponseEntity.ok(taskRuns);
        }
        // Sleep for a short interval before checking again
        Thread.sleep(MAX_SLEEP_INTERVAL);
      } catch (Exception e) {
        LOGGER.error("Error retrieving tasks for agent {}: {}", agentId, e.getMessage());
      }
    }
    LOGGER.debug("Ending long poll queue for agent: {}", agentId);
    return ResponseEntity.noContent().build();
  }
}
