package io.boomerang.client;

import io.boomerang.common.enums.RunPhase;
import io.boomerang.dispatcher.QueueService;
import io.boomerang.dispatcher.TaskSlots;
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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Service
public class EngineClient {

  private static final Logger LOGGER = LogManager.getLogger(EngineClient.class);

  // Delay between one queue poll returning and the next starting. The engine holds an empty poll
  // open for up to 30 s, so reconnecting at once costs nothing while idle and leaves no gap in
  // which newly ready work waits for this dispatcher. (@Scheduled needs a positive delay.)
  private static final long RECONNECT_DELAY_MS = 1L;

  // An empty or failed poll starts the next one no sooner than this after it started, so an
  // engine that answers at once - claiming switched off, no task types, unreachable - is asked
  // every 5 s rather than in a tight loop.
  static final long MIN_IDLE_POLL_SPACING_MS = 5000L;

  private String dispatcherHost;

  private String dispatcherId;

  @Value("${flow.engine.workflowrun.start.url}")
  private String startWorkflowRunURL;

  @Value("${flow.engine.workspace.releasable.url}")
  private String releasableWorkspacesURL;

  @Value("${flow.engine.taskrun.start.url}")
  private String startTaskRunURL;

  @Value("${flow.engine.taskrun.end.url}")
  private String endTaskRunURL;

  @Value("${flow.engine.dispatcher.register.url}")
  private String dispatcherRegisterURL;

  @Value("${flow.engine.dispatcher.heartbeat.url}")
  private String dispatcherHeartbeatURL;

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

  @Autowired public TaskSlots taskSlots;

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

  /**
   * Ask the engine which of the workspace volumes this dispatcher holds may be released: a
   * workflowRunRef comes back once its run is completed or gone, a workflowRef once its workflow is
   * deleted or gone. A failure is not fatal - the volumes stay held and the next tick asks again.
   */
  public WorkspaceReleaseResponse releasableWorkspaces(WorkspaceReleaseQuery query) {
    try {
      final HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      HttpEntity<WorkspaceReleaseQuery> entity = new HttpEntity<>(query, headers);
      ResponseEntity<WorkspaceReleaseResponse> response =
          restTemplate.exchange(
              releasableWorkspacesURL, HttpMethod.POST, entity, WorkspaceReleaseResponse.class);
      return (response.getBody() != null ? response.getBody() : new WorkspaceReleaseResponse());
    } catch (Exception e) {
      LOGGER.warn("Error retrieving releasable workspaces: {}", e.getMessage());
      return new WorkspaceReleaseResponse();
    }
  }

  // Start and end carry this dispatcher's registered id so the engine can fence a request from a
  // dispatcher whose claim has since been superseded (claim.by no longer matches).
  /**
   * Tell the engine the TaskRun is starting, and return whether to go ahead and create it. Not when
   * the engine answers that it is already finished - cancelled while it was being handed over - or
   * refuses the request, as it does a superseded claim. When the engine cannot be reached the
   * TaskRun still goes ahead, as it always has: its end is fenced on the claim either way.
   */
  public boolean startTask(String taskRunId) {
    try {
      String url = startTaskRunURL.replace("{taskRunId}", taskRunId);
      final HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      TaskRunStartRequest startRequest = new TaskRunStartRequest();
      startRequest.setDispatcherRef(dispatcherId);
      HttpEntity<TaskRunStartRequest> entity = new HttpEntity<>(startRequest, headers);
      ResponseEntity<TaskRun> response =
          restTemplate.exchange(url, HttpMethod.PUT, entity, TaskRun.class);

      LOGGER.info(response.getStatusCode());
      return response.getBody() == null
          || !RunPhase.completed.equals(response.getBody().getPhase());
    } catch (HttpClientErrorException ex) {
      LOGGER.warn("Engine refused to start TaskRun ({}): {}", taskRunId, ex.getMessage());
      return false;
    } catch (RestClientException ex) {
      LOGGER.error(ex.toString());
      return true;
    }
  }

  public void endTask(String taskRunId, TaskRunEndRequest endRequest) {
    try {
      String url = endTaskRunURL.replace("{taskRunId}", taskRunId);
      final HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      endRequest.setDispatcherRef(dispatcherId);
      HttpEntity<TaskRunEndRequest> entity = new HttpEntity<TaskRunEndRequest>(endRequest, headers);
      ResponseEntity<Void> response =
          restTemplate.exchange(url, HttpMethod.PUT, entity, Void.class);

      LOGGER.info(response.getStatusCode());
    } catch (RestClientException ex) {
      LOGGER.error(ex.toString());
    }
  }

  /**
   * Reports the TaskRun ids this dispatcher's watch loops are still polling, so the engine can
   * renew their lease. A missed beat is the engine's signal, never fatal to the dispatcher - any
   * failure is logged and swallowed.
   */
  public void heartbeat(List<String> ids) {
    try {
      String url = dispatcherHeartbeatURL.replace("{dispatcherId}", dispatcherId);
      final HttpHeaders headers = new HttpHeaders();
      headers.setContentType(MediaType.APPLICATION_JSON);
      HttpEntity<HeartbeatRequest> entity = new HttpEntity<>(new HeartbeatRequest(ids), headers);
      restTemplate.exchange(url, HttpMethod.PUT, entity, Void.class);
    } catch (Exception e) {
      LOGGER.warn("Error sending dispatcher heartbeat: {}", e.getMessage());
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
      dispatcherHost = InetAddress.getLocalHost().getHostName();
      LOGGER.debug("Registering Dispatcher({})", dispatcherHost);

      DispatcherRegistrationRequest request =
          new DispatcherRegistrationRequest(dispatcherName, dispatcherHost, taskTypes);

      // Send the registration request
      ResponseEntity<String> response =
          restTemplate.postForEntity(dispatcherRegisterURL, request, String.class);
      if (!response.getStatusCode().is2xxSuccessful()) {
        LOGGER.error(
            "Failed to register Dispatcher({}). Status: {}", dispatcherHost, response.getStatusCode());
        throw new RuntimeException(
            "Failed to register Dispatcher: "
                + dispatcherHost
                + ". Status: "
                + response.getStatusCode());
      }
      dispatcherId = response.getBody();
      LOGGER.debug("Dispatcher {}({}) registered successfully.", dispatcherId, dispatcherHost);
    } catch (UnknownHostException e) {
      throw new RuntimeException("Failed to retrieve hostname for machine ID", e);
    } catch (Exception e) {
      throw new RuntimeException("Error during Dispatcher registration: " + e.getMessage());
    }
  }

  @Scheduled(fixedDelay = RECONNECT_DELAY_MS)
  public void retrieveDispatcherWorkflowQueue() {
    retrieveDispatcherQueue(dispatcherQueueWorkflowURL, true);
  }

  @Scheduled(fixedDelay = RECONNECT_DELAY_MS)
  public void retrieveDispatcherTaskQueue() {
    Integer free = taskSlots.free();
    retrieveDispatcherQueue(
        (free == null) ? dispatcherQueueTaskURL : dispatcherQueueTaskURL + "?limit=" + free, false);
  }

  /**
   * Long-poll one queue. The engine holds the request open until it has claimed runs for this
   * dispatcher (200) or its window passes with nothing to hand out (204).
   *
   * <p>A poll that brought runs returns at once so the next one starts straight away; an empty or
   * failed poll first waits out the rest of {@link #MIN_IDLE_POLL_SPACING_MS} from its start.
   *
   * <p>TODO in the future optimise the Async to have a LinkedBlockingQueue with maximum size of
   * what it can achieve
   */
  private void retrieveDispatcherQueue(String queueURL, boolean isWorkflow) {
    long startedMillis = System.currentTimeMillis();
    LOGGER.info(
        "Retrieving {}Runs Queue for Dispatcher ({})", isWorkflow ? "Workflow" : "Task", dispatcherId);
    try {
      String url = queueURL.replace("{dispatcherId}", dispatcherId);
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
                // Taken here, before the hand-off, so the next poll - which starts at once - already
                // counts it; QueueService gives it back when the executor work ends.
                if (TaskSlots.isExecutionClaim((TaskRun) run)) {
                  taskSlots.take();
                }
                queueService.processTaskRun((TaskRun) run);
              }
            });
        if (!runs.isEmpty()) {
          return;
        }
      } else if (response.getStatusCode().isSameCodeAs(HttpStatusCode.valueOf(204))) {
        LOGGER.debug("Queue returned 204 - No content.");
      }
    } catch (Exception e) {
      LOGGER.warn("Error retrieving queue: {}", e.getMessage());
    }
    long waitMillis = startedMillis + MIN_IDLE_POLL_SPACING_MS - System.currentTimeMillis();
    if (waitMillis > 0) {
      try {
        Thread.sleep(waitMillis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
