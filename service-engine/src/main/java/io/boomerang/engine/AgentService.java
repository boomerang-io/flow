package io.boomerang.engine;

import static io.boomerang.util.ConvertUtil.entityToModel;

import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
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
import java.util.List;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

@Service
public class AgentService {
  private static final Logger LOGGER = LogManager.getLogger();

  private static final Integer MAX_POLL_INTERVAL = 30000;
  private static final Integer MAX_SLEEP_INTERVAL = 1000; // 1 sec

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
   * Retrieves the workflowruns and taskruns for the agent. This is a long poll endpoint.
   *
   * <p>TODO: figure out how to have multiple agents, probably a centralised lock, so no chance of
   * collision in retrieving and assigning
   *
   * @param agentId
   * @return
   */
  public ResponseEntity<List<WorkflowRun>> getWorkflowQueue(String agentId) {
    // Validate the Agent
    if (!agentRepository.existsById(agentId)) {
      LOGGER.error("Agent {} not registered", agentId);
      throw new IllegalArgumentException("Agent ID does not exist or is not registered.");
    }
    agentRepository.updateLastConnected(agentId, new Date());
    // TODO add in future filtering of workflows based on labels or a setting
    //    AgentEntity entity = agentRepository.findTaskTypesByAgentId(agentId);
    //    if (entity != null && entity.getTaskTypes() != null && entity.getTaskTypes().isEmpty()) {
    //      LOGGER.warn("Agent {} has no task types defined. Returning 204.", agentId);
    //      return ResponseEntity.noContent().build();
    //    }
    //    LOGGER.debug("Entity: {}", entity);

    // Long poll logic
    Instant endTime = Instant.now().plusMillis(MAX_POLL_INTERVAL); // Keep connection open
    LOGGER.debug("Starting long poll queue for agent: {}", agentId);
    while (Instant.now().isBefore(endTime)) {
      LOGGER.debug("Checking queue for agent: {}", agentId);
      try {
        // Retrieve workflows and tasks for the agent filtered as per agents capabilities
        // Stream, convert, and collect WorkflowRunEntity to WorkflowRun
        List<WorkflowRun> workflowRuns =
            wfRunRepository
                .findByPhaseInAndStatusInOrPhaseIn(
                    List.of(RunPhase.pending),
                    List.of(RunStatus.ready),
                    List.of(RunPhase.completed))
                .stream()
                .map((e) -> entityToModel(e, WorkflowRun.class))
                .collect(Collectors.toList());

        // Update the WorkflowRuns so that they are assigned to the agent and set to queued phase
        wfRunRepository.updatePhaseAndAgentRef(
            List.of(RunPhase.pending), List.of(RunStatus.ready), agentId, RunPhase.queued);

        LOGGER.debug("Found {} WorkflowRuns for Agent: {}", workflowRuns.size(), agentId);
        if (workflowRuns.size() > 0) {
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
   * Retrieves the workflowruns and taskruns for the agent. This is a long poll endpoint.
   *
   * <p>TODO: figure out how to have multiple agents, probably a centralised lock, so no chance of
   * collision in retrieving and assigning
   *
   * @param agentId
   * @return
   */
  public ResponseEntity<List<TaskRun>> getTaskQueue(String agentId) {
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
        // Stream, convert, and collect TaskRuns that are ready
        List<TaskRun> taskRuns =
            taskRunRepository
                .findByPhaseInAndStatusInAndTypeIn(
                    List.of(RunPhase.pending, RunPhase.completed),
                    List.of(RunStatus.ready, RunStatus.cancelled, RunStatus.timedout),
                    entity.getTaskTypes())
                .stream()
                .map((e) -> new TaskRun(e))
                .collect(Collectors.toList());

        taskRuns.forEach(tr -> LOGGER.debug("TaskRun: {}", tr));

        // Update the TaskRuns so that they are assigned to the agent and set to queued phase
        taskRunRepository.updatePhaseAndAgentRef(
            List.of(RunPhase.pending),
            List.of(RunStatus.ready),
            entity.getTaskTypes(),
            agentId,
            RunPhase.queued);

        LOGGER.debug("Found {} TaskRuns for Agent: {}", taskRuns.size(), agentId);
        if (taskRuns.size() > 0) {
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
