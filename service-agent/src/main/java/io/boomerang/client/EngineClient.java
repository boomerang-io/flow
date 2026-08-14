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

  @Value("${flow.engine.dispatcher.register.url}")
  private String dispatcherRegisterURL;

  @Value("${flow.engine.dispatcher.workflowqueue.url}")
  private String dispatcherQueueWorkflowURL;

  @Value("${flow.engine.dispatcher.taskqueue.url}")
  private String dispatcherQueueTaskURL;

  @Value("${flow.dispatcher.task-types}")
  private List<String> taskTypes;

  @Value("${flow.dispatcher.name}")
  private String dispatcherName;

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
   * Registers the dispatcher and its capabilities with the engine
   *
   * <p>This should block and cause the service to exit if it cannot register
   */
  public void registerDispatcher() {
    try {
      // Retrieve the hostname as the machine ID
      agentHost = InetAddress.getLocalHost().getHostName();
      LOGGER.debug("Registering Dispatcher({})", agentHost);

      AgentRegistrationRequest request =
          new AgentRegistrationRequest(dispatcherName, agentHost, taskTypes);

      // Send the registration request
      ResponseEntity<String> response =
          restTemplate.postForEntity(dispatcherRegisterURL, request, String.class);
      if (!response.getStatusCode().is2xxSuccessful()) {
        LOGGER.error(
            "Failed to register Dispatcher({}). Status: {}", agentHost, response.getStatusCode());
        throw new RuntimeException(
            "Failed to register Dispatcher: "
                + agentHost
                + ". Status: "
                + response.getStatusCode());
      }
      agentId = response.getBody();
      LOGGER.debug("Dispatcher {}({}) registered successfully.", agentId, agentHost);
    } catch (UnknownHostException e) {
      throw new RuntimeException("Failed to retrieve hostname for machine ID", e);
    } catch (Exception e) {
      throw new RuntimeException("Error during Dispatcher registration: " + e.getMessage());
    }
  }

  @Scheduled(fixedDelay = HEARTBEAT_INTERVAL)
  public void retrieveDispatcherWorkflowQueue() {
    String url = dispatcherQueueWorkflowURL.replace("{agentId}", agentId);
    retrieveDispatcherQueue(url, true);
  }

  @Scheduled(fixedDelay = HEARTBEAT_INTERVAL)
  public void retrieveDispatcherTaskQueue() {
    String url = dispatcherQueueTaskURL.replace("{agentId}", agentId);
    retrieveDispatcherQueue(url, false);
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
  private void retrieveDispatcherQueue(String url, boolean isWorkflow) {
    LOGGER.info(
        "Retrieving {}Runs Queue for Dispatcher ({})", isWorkflow ? "Workflow" : "Task", agentId);
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
