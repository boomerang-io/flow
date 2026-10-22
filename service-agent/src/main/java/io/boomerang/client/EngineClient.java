package io.boomerang.client;

import io.boomerang.agent.QueueService;
import io.boomerang.common.model.*;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Service
public class EngineClient {

  private static final Logger LOGGER = LogManager.getLogger(EngineClient.class);

  private static final long HEARTBEAT_INTERVAL = 5000L; // 5 seconds

  private String agentHost;

  private String agentId;

  @Value("${flow.engine.workflowrun.start.url}")
  private String startWorkflowRunURL;

  @Value("${flow.engine.workflowrun.finalize.url}")
  private String finalizeWorkflowRunURL;

  @Value("${flow.engine.taskrun.start.url}")
  private String startTaskRunURL;

  @Value("${flow.engine.taskrun.end.url}")
  private String endTaskRunURL;

  @Value("${flow.engine.agent.register.url}")
  private String agentRegisterURL;

  @Value("${flow.engine.agent.workflowqueue.url}")
  private String agentQueueWorkflowURL;

  @Value("${flow.engine.agent.taskqueue.url}")
  private String agentQueueTaskURL;

  @Value("${flow.agent.task-types}")
  private List<String> taskTypes;

  @Value("${flow.agent.name}")
  private String agentName;

  @Autowired
  @Qualifier("internalRestTemplate")
  public RestTemplate restTemplate;

  @Autowired public QueueService queueService;

  public void startWorkflow(String wfRunId) {
    try {
      String url = startWorkflowRunURL.replace("{workflowRunId}", wfRunId);
      final HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      HttpEntity<String> entity = new HttpEntity<String>("{}", headers);
      ResponseEntity<Void> response =
          restTemplate.exchange(url, HttpMethod.PUT, entity, Void.class);

      LOGGER.info(response.getStatusCode());
    } catch (RestClientException ex) {
      LOGGER.error(ex.toString());
    }
  }

  public void finalizeWorkflow(String wfRunId) {
    try {
      String url = finalizeWorkflowRunURL.replace("{workflowRunId}", wfRunId);
      final HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      HttpEntity<String> entity = new HttpEntity<String>("{}", headers);
      ResponseEntity<Void> response =
          restTemplate.exchange(url, HttpMethod.PUT, entity, Void.class);

      LOGGER.info(response.getStatusCode());
    } catch (RestClientException ex) {
      LOGGER.error(ex.toString());
    }
  }

  public void startTask(String taskRunId) {
    try {
      String url = startTaskRunURL.replace("{taskRunId}", taskRunId);
      final HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      HttpEntity<String> entity = new HttpEntity<String>("{}", headers);
      ResponseEntity<Void> response =
          restTemplate.exchange(url, HttpMethod.PUT, entity, Void.class);

      LOGGER.info(response.getStatusCode());
    } catch (RestClientException ex) {
      LOGGER.error(ex.toString());
    }
  }

  public void endTask(String taskRunId, TaskRunEndRequest endRequest) {
    try {
      String url = endTaskRunURL.replace("{taskRunId}", taskRunId);
      final HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      HttpEntity<TaskRunEndRequest> entity = new HttpEntity<TaskRunEndRequest>(endRequest, headers);
      ResponseEntity<Void> response =
          restTemplate.exchange(url, HttpMethod.PUT, entity, Void.class);

      LOGGER.info(response.getStatusCode());
    } catch (RestClientException ex) {
      LOGGER.error(ex.toString());
    }
  }

  /**
   * Registers the agent and its capabilities with the engine
   *
   * <p>This should block and cause the service to exit if it cannot register
   */
  public void registerAgent() {
    try {
      // Retrieve the hostname as the machine ID
      agentHost = InetAddress.getLocalHost().getHostName();
      LOGGER.debug("Registering Agent({})", agentHost);

      AgentRegistrationRequest request =
          new AgentRegistrationRequest(agentName, agentHost, taskTypes);

      // Send the registration request
      ResponseEntity<String> response =
          restTemplate.postForEntity(agentRegisterURL, request, String.class);
      if (!response.getStatusCode().is2xxSuccessful()) {
        LOGGER.error(
            "Failed to register Agent({}). Status: {}", agentHost, response.getStatusCode());
        throw new RuntimeException(
            "Failed to register Agent: " + agentHost + ". Status: " + response.getStatusCode());
      }
      agentId = response.getBody();
      LOGGER.debug("Agent {}({}) registered successfully.", agentId, agentHost);
    } catch (UnknownHostException e) {
      throw new RuntimeException("Failed to retrieve hostname for machine ID", e);
    } catch (Exception e) {
      throw new RuntimeException("Error during Agent registration: " + e.getMessage());
    }
  }

  @Scheduled(fixedDelay = HEARTBEAT_INTERVAL)
  public void retrieveAgentWorkflowQueue() {
    String url = agentQueueWorkflowURL.replace("{agentId}", agentId);
    retrieveAgentQueue(url, true);
  }

  @Scheduled(fixedDelay = HEARTBEAT_INTERVAL)
  public void retrieveAgentTaskQueue() {
    String url = agentQueueTaskURL.replace("{agentId}", agentId);
    retrieveAgentQueue(url, false);
  }

  /**
   * Implements a heartbeat style queue check
   *
   * <p>200 means there are workflow runs available
   *
   * <p>204 means there are no workflow runs available
   *
   * <p>TODO in the future optimise the Async to have a LinkedBlockingQueue with maximum size of
   * what it can achieve
   */
  private void retrieveAgentQueue(String url, boolean isWorkflow) {
    LOGGER.info(
        "Retrieving {}Runs Queue for Agent ({})", isWorkflow ? "Workflow" : "Task", agentId);
    try {
      ResponseEntity<?> response =
          restTemplate.exchange(
              url,
              HttpMethod.GET,
              null,
              (ParameterizedTypeReference<? extends List<?>>)
                  (isWorkflow
                      ? new ParameterizedTypeReference<List<WorkflowRun>>() {}
                      : new ParameterizedTypeReference<List<TaskRun>>() {}));
      if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
        List<?> runs = (List<?>) response.getBody();
        LOGGER.info("Received {} {}Runs.", runs.size(), isWorkflow ? "Workflow" : "Task");
        runs.forEach(
            run -> {
              LOGGER.debug(
                  "Processing {}Run: {}", isWorkflow ? "Workflow" : "Task", run.toString());
              if (isWorkflow) {
                queueService.processWorkflowRun((WorkflowRun) run);
              } else {
                queueService.processTaskRun((TaskRun) run);
              }
            });
      } else if (response.getStatusCode().isSameCodeAs(HttpStatusCode.valueOf(204))) {
        LOGGER.debug("Queue returned 204 - No content.");
      }
    } catch (Exception e) {
      LOGGER.warn("Error retrieving queue: {}", e.getMessage());
    }
  }
}
