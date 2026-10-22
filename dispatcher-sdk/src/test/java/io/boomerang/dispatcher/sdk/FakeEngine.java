package io.boomerang.dispatcher.sdk;

import io.boomerang.dispatcher.sdk.model.DispatcherRegistrationRequest;
import io.boomerang.dispatcher.sdk.model.TaskRun;
import io.boomerang.dispatcher.sdk.model.TaskRunEndRequest;
import io.boomerang.dispatcher.sdk.model.TaskRunStartRequest;
import io.boomerang.dispatcher.sdk.model.WorkflowRun;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * An engine in memory: records every call the SDK makes and answers from a script, one answer per
 * call, falling back to success.
 */
class FakeEngine extends DispatcherClient {

  final List<String> registrations = new CopyOnWriteArrayList<>();
  final List<String> starts = new CopyOnWriteArrayList<>();
  final Map<String, List<TaskRunEndRequest>> ends = new ConcurrentHashMap<>();
  final List<List<String>> heartbeats = new CopyOnWriteArrayList<>();
  final List<String> workflowStarts = new CopyOnWriteArrayList<>();
  final List<String> taskPollIds = new CopyOnWriteArrayList<>();
  final List<Integer> taskPollLimits = new CopyOnWriteArrayList<>();
  final List<TaskFilter> taskPollFilters = new CopyOnWriteArrayList<>();
  final List<String> workflowPolls = new CopyOnWriteArrayList<>();

  final Deque<Supplier<String>> registerAnswers = new ArrayDeque<>();
  final Deque<Supplier<TaskRun>> startAnswers = new ArrayDeque<>();
  final Queue<Runnable> endAnswers = new ConcurrentLinkedQueue<>();
  final Queue<RuntimeException> taskPollRefusals = new ConcurrentLinkedQueue<>();
  final Queue<RuntimeException> heartbeatRefusals = new ConcurrentLinkedQueue<>();
  final Queue<List<TaskRun>> taskPolls = new ConcurrentLinkedQueue<>();
  final Queue<List<WorkflowRun>> workflowPollAnswers = new ConcurrentLinkedQueue<>();
  final Map<String, String> logs = new ConcurrentHashMap<>();
  final Queue<Runnable> logAnswers = new ConcurrentLinkedQueue<>();

  FakeEngine() {
    super(RestClient.create(), "http://fake-engine", null);
  }

  static RestClientException unreachable() {
    return new ResourceAccessException("Connection refused");
  }

  static RestClientException refused(HttpStatus status) {
    return HttpClientErrorException.create(
        status, status.getReasonPhrase(), HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
  }

  static RestClientException failing() {
    return HttpServerErrorException.create(
        HttpStatus.SERVICE_UNAVAILABLE, "Unavailable", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
  }

  /** The engine's answer when log storage is switched off: 503 with the standard error body. */
  static RestClientException logsDisabled() {
    byte[] body =
        ("{\"code\":1508,\"reason\":\"TASKRUN_LOGS_DISABLED\",\"status\":\"503 SERVICE_UNAVAILABLE\"}")
            .getBytes(StandardCharsets.UTF_8);
    return HttpServerErrorException.create(
        HttpStatus.SERVICE_UNAVAILABLE, "Unavailable", HttpHeaders.EMPTY, body, StandardCharsets.UTF_8);
  }

  /** The engine's answer when it has no artifact store: 503 with the standard error body. */
  static RestClientException noArtifactStore() {
    byte[] body =
        ("{\"code\":2007,\"reason\":\"ARTIFACTS_NOT_CONFIGURED\",\"status\":\"503 SERVICE_UNAVAILABLE\"}")
            .getBytes(StandardCharsets.UTF_8);
    return HttpServerErrorException.create(
        HttpStatus.SERVICE_UNAVAILABLE, "Unavailable", HttpHeaders.EMPTY, body, StandardCharsets.UTF_8);
  }

  List<TaskRunEndRequest> endsOf(String taskRunId) {
    return ends.getOrDefault(taskRunId, List.of());
  }

  @Override
  public synchronized String register(DispatcherRegistrationRequest request) {
    registrations.add(request.getName());
    Supplier<String> answer = registerAnswers.poll();
    return (answer != null) ? answer.get() : "d-1";
  }

  @Override
  public List<TaskRun> pollTasks(String dispatcherId, Integer limit, TaskFilter filter) {
    taskPollIds.add(dispatcherId);
    taskPollLimits.add(limit);
    taskPollFilters.add(filter);
    RuntimeException refusal = taskPollRefusals.poll();
    if (refusal != null) {
      throw refusal;
    }
    List<TaskRun> answer = taskPolls.poll();
    return (answer != null) ? answer : List.of();
  }

  @Override
  public List<WorkflowRun> pollWorkflows(String dispatcherId) {
    workflowPolls.add(dispatcherId);
    List<WorkflowRun> answer = workflowPollAnswers.poll();
    return (answer != null) ? answer : List.of();
  }

  @Override
  public synchronized TaskRun startTask(String taskRunId, TaskRunStartRequest request) {
    starts.add(taskRunId + ":" + request.getDispatcherRef());
    Supplier<TaskRun> answer = startAnswers.poll();
    return (answer != null) ? answer.get() : null;
  }

  @Override
  public void endTask(String taskRunId, TaskRunEndRequest request) {
    ends.computeIfAbsent(taskRunId, id -> new CopyOnWriteArrayList<>()).add(request);
    Runnable answer = endAnswers.poll();
    if (answer != null) {
      answer.run();
    }
  }

  @Override
  public void heartbeat(String dispatcherId, List<String> taskRunIds) {
    heartbeats.add(taskRunIds);
    RuntimeException refusal = heartbeatRefusals.poll();
    if (refusal != null) {
      throw refusal;
    }
  }

  @Override
  public void uploadLog(String dispatcherId, String taskRunId, byte[] log) {
    Runnable answer = logAnswers.poll();
    if (answer != null) {
      answer.run();
    }
    logs.put(taskRunId, new String(log, StandardCharsets.UTF_8));
  }

  @Override
  public void startWorkflowRun(String workflowRunId) {
    workflowStarts.add(workflowRunId);
  }
}
