package io.boomerang.dispatcher.sdk;

import io.boomerang.dispatcher.sdk.model.RunPhase;
import io.boomerang.dispatcher.sdk.model.RunResult;
import io.boomerang.dispatcher.sdk.model.RunStatus;
import io.boomerang.dispatcher.sdk.model.TaskRun;
import io.boomerang.dispatcher.sdk.model.TaskRunEndRequest;
import io.boomerang.dispatcher.sdk.model.TaskRunStartRequest;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.Kind;
import io.micrometer.observation.transport.ReceiverContext;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/**
 * One task run's lifecycle, from the claim to the end the engine accepts.
 *
 * <ul>
 *   <li>An order to run ({@code queued}, {@code ready}) of a registered type is taken into flight
 *       on the poller's thread, so the next poll already counts it, then run on a virtual thread:
 *       start, the handler, the end.
 *   <li>The start answer decides whether it runs: not when the engine answers phase {@code
 *       completed} or a 4xx, and still when the engine cannot be reached - the end is fenced on
 *       the claim either way.
 *   <li>The end is retried with backoff until the engine accepts it or refuses it with a 4xx; the
 *       task stays in flight, its lease renewed, until then, so an end the engine never received
 *       is never mistaken for a lost task and run again.
 *   <li>An order to terminate ({@code completed}, {@code cancelled} or {@code timedout}) signals
 *       the running task's context and calls the handler's cancel. No end is reported for it, nor
 *       for a task whose context was signalled.
 *   <li>A task interrupted because the dispatcher stopped past its drain timeout reports nothing
 *       either: its lease lapses and the engine hands it out again.
 *   <li>A claim is taken into flight and handed off as one step against {@link #stop}: once the
 *       dispatcher is stopping a claim is refused before it is taken, never dropped after.
 *   <li>The handler runs inside an observation named {@value #OBSERVATION} carrying the task's
 *       trace context, so its spans join the run's trace; a no-op without a registry. With a
 *       {@link TaskLogCapture} the lines it logs are its task log, uploaded as it ends and every
 *       upload interval while it runs.
 * </ul>
 */
final class TaskRunner {

  /** The name of the observation each task runs under. */
  static final String OBSERVATION = "flow.task";

  private static final Log LOGGER = LogFactory.getLog(TaskRunner.class);

  private final DispatcherClient client;
  private final Supplier<String> dispatcherId;
  private final TaskHandler handler;
  private final InFlightTasks inFlight;
  private final Map<String, TaskContext> running;
  private final Predicate<TaskRun> registered;
  private final Function<TaskRun, ArtifactTransfer> artifacts;
  private final LogUploader logs;
  private final TaskLogCapture capture;
  private final Duration logUploadInterval;
  private final ObservationRegistry observations;
  private final Executor executor;
  private final Backoff endBackoff;
  private final BooleanSupplier abandoned;

  // Read by every accept, written by stop: a claim either reaches the executor before the stop
  // or is refused before it is taken into flight.
  private final ReentrantReadWriteLock stopping = new ReentrantReadWriteLock();
  private boolean stopped;

  /**
   * @param running the contexts of the tasks running in this dispatcher, shared by every runner so
   *     a terminate order reaches the task wherever it runs
   * @param registered whether a task run's type is one this dispatcher registered
   * @param logs stores a task's finished log with the engine, shared so that an engine with no
   *     store is asked once
   * @param capture the buffer the handler's logged lines land in, or null to capture none
   * @param logUploadInterval how often a captured log is uploaded while the task runs; zero for
   *     only at the end
   * @param observations where the task observation is registered; {@link
   *     ObservationRegistry#NOOP} opens none
   * @param abandoned whether the dispatcher has stopped past its drain timeout and interrupted the
   *     tasks still running, which then report nothing
   */
  TaskRunner(
      DispatcherClient client,
      Supplier<String> dispatcherId,
      TaskHandler handler,
      InFlightTasks inFlight,
      Map<String, TaskContext> running,
      Predicate<TaskRun> registered,
      Function<TaskRun, ArtifactTransfer> artifacts,
      LogUploader logs,
      TaskLogCapture capture,
      Duration logUploadInterval,
      ObservationRegistry observations,
      Executor executor,
      Backoff endBackoff,
      BooleanSupplier abandoned) {
    this.client = client;
    this.dispatcherId = dispatcherId;
    this.handler = handler;
    this.inFlight = inFlight;
    this.running = running;
    this.registered = registered;
    this.artifacts = artifacts;
    this.logs = logs;
    this.capture = capture;
    this.logUploadInterval = logUploadInterval;
    this.observations = observations;
    this.executor = executor;
    this.endBackoff = endBackoff;
    this.abandoned = abandoned;
  }

  /** Take one claim off a poll. Returns at once; the work runs on a virtual thread. */
  void accept(TaskRun task) {
    stopping.readLock().lock();
    try {
      if (stopped) {
        LOGGER.warn("TaskRun (" + task.getId() + ") not taken: the dispatcher is stopping.");
      } else if (task.isRunOrder() && registered.test(task)) {
        if (!inFlight.add(task.getId())) {
          LOGGER.warn(
              "TaskRun (" + task.getId() + ") is already in flight here. Ignoring the claim.");
          return;
        }
        execute(task, () -> run(task), () -> inFlight.remove(task.getId()));
      } else if (task.isTerminateOrder()) {
        execute(task, () -> terminate(task), () -> {});
      } else {
        LOGGER.info(
            "Skipping "
                + task
                + ": neither an order to run a registered type nor an order to terminate.");
      }
    } finally {
      stopping.readLock().unlock();
    }
  }

  /**
   * Refuse every claim from now on. A claim being taken as this is called is in flight and with
   * the executor before this returns, so the executor can be shut down afterwards with no claim
   * registered here and not taken.
   */
  void stop() {
    stopping.writeLock().lock();
    try {
      stopped = true;
    } finally {
      stopping.writeLock().unlock();
    }
  }

  private void execute(TaskRun task, Runnable work, Runnable rejected) {
    try {
      executor.execute(work);
    } catch (RuntimeException e) {
      // The executor was shut down without a stop: the claim lapses and the engine hands the
      // task out again.
      rejected.run();
      LOGGER.warn("TaskRun (" + task.getId() + ") not taken: " + e.getMessage());
    }
  }

  void run(TaskRun task) {
    String id = task.getId();
    try {
      if (!start(task)) {
        return;
      }
      TaskContext context =
          new TaskContext(task, dispatcherId.get(), artifacts.apply(task), logs);
      running.put(id, context);
      TaskRunEndRequest end;
      try {
        LOGGER.info("TaskRun (" + id + ") running.");
        end = handle(task, context);
      } finally {
        running.remove(id);
      }
      if (context.isCancelled()) {
        LOGGER.info("TaskRun (" + id + ") was terminated by the engine. Reporting no end.");
        return;
      }
      if (abandoned.getAsBoolean()) {
        // Interrupted by a stop past its drain: the work may still finish where it runs, so its
        // lease lapses and the engine hands it out again rather than recording a failure.
        LOGGER.warn("TaskRun (" + id + ") left to the engine; the dispatcher stopped.");
        return;
      }
      end(id, end);
    } finally {
      inFlight.remove(id);
    }
  }

  // The handler call, as the end it yields: under the task's observation and, when capture is
  // on, tagged in the MDC with its log uploaded at the end and every interval while it runs.
  private TaskRunEndRequest handle(TaskRun task, TaskContext context) {
    String id = task.getId();
    Observation observation = observe(task).start();
    Thread uploads = null;
    if (capture != null) {
      capture.open(id);
      uploads = reuploading(task);
    }
    try (Observation.Scope scope = observation.openScope()) {
      return succeeded(handler.run(task, context));
    } catch (TaskFailure e) {
      observation.error(e);
      return failed(e.getStatusReason(), e.getMessage(), e.getResults());
    } catch (Exception e) {
      observation.error(e);
      LOGGER.error("TaskRun (" + id + ") handler failed.", e);
      return failed(
          StatusReason.DISPATCH_ERROR,
          (e.getMessage() != null) ? e.getMessage() : e.toString(),
          List.of());
    } finally {
      observation.stop();
      if (capture != null) {
        if (uploads != null) {
          uploads.interrupt();
        }
        logs.upload(task, capture.close(id));
      }
    }
  }

  // The task observation, carrying the traceparent for the host's tracing handler to parent it
  // on; a no-op without a registry.
  private Observation observe(TaskRun task) {
    ReceiverContext<Map<String, String>> context =
        new ReceiverContext<>((carrier, key) -> carrier.get(key), Kind.CONSUMER);
    String traceparent = task.traceparent();
    context.setCarrier((traceparent != null) ? Map.of("traceparent", traceparent) : Map.of());
    return Observation.createNotStarted(OBSERVATION, () -> context, observations)
        .lowCardinalityKeyValue("flow.taskrun.id", String.valueOf(task.getId()))
        .lowCardinalityKeyValue("flow.task.name", String.valueOf(task.getName()))
        .lowCardinalityKeyValue("flow.workflowrun.id", String.valueOf(task.getWorkflowRunRef()));
  }

  // A long task's log is readable before it ends: the captured log so far, every interval.
  private Thread reuploading(TaskRun task) {
    if (!logUploadInterval.isPositive()) {
      return null;
    }
    return Thread.ofVirtual()
        .name("dispatcher-log-" + task.getId())
        .start(
            () -> {
              while (Backoff.sleep(logUploadInterval)) {
                logs.upload(task, capture.snapshot(task.getId()));
              }
            });
  }

  /** Tell the engine the task is starting; return whether to go ahead and run it. */
  boolean start(TaskRun task) {
    try {
      TaskRun answer =
          client.startTask(task.getId(), new TaskRunStartRequest(dispatcherId.get()));
      if (answer != null && RunPhase.completed.equals(answer.getPhase())) {
        LOGGER.info("TaskRun (" + task.getId() + ") is no longer to be run. Not running it.");
        return false;
      }
      return true;
    } catch (HttpClientErrorException e) {
      LOGGER.warn("Engine refused to start TaskRun (" + task.getId() + "): " + e.getMessage());
      return false;
    } catch (RuntimeException e) {
      LOGGER.warn(
          "Engine unreachable starting TaskRun ("
              + task.getId()
              + "); running it anyway: "
              + e.getMessage());
      return true;
    }
  }

  /** Report the end until the engine accepts it or refuses it with a 4xx. */
  void end(String id, TaskRunEndRequest end) {
    end.setDispatcherRef(dispatcherId.get());
    Duration delay = endBackoff.initial();
    for (int attempt = 1; ; attempt++) {
      try {
        client.endTask(id, end);
        LOGGER.info("TaskRun (" + id + ") ended " + end.getStatus() + ".");
        return;
      } catch (HttpClientErrorException e) {
        if (!isTransient(e)) {
          LOGGER.warn("Engine refused the end of TaskRun (" + id + "): " + e.getMessage());
          return;
        }
        LOGGER.warn(retrying(id, attempt, delay, e));
      } catch (RestClientException e) {
        LOGGER.warn(retrying(id, attempt, delay, e));
      } catch (RuntimeException e) {
        LOGGER.error("TaskRun (" + id + ") end could not be sent.", e);
        return;
      }
      if (!Backoff.sleep(delay)) {
        LOGGER.error("TaskRun (" + id + ") end not delivered before the dispatcher stopped.");
        return;
      }
      delay = endBackoff.next(delay);
    }
  }

  void terminate(TaskRun task) {
    TaskContext context = running.get(task.getId());
    if (context != null) {
      context.cancel();
    }
    try {
      LOGGER.info("TaskRun (" + task.getId() + ") terminating.");
      handler.cancel(task);
    } catch (Exception e) {
      // A terminate order has no end to report, and nothing left to stop is the ordinary case.
      LOGGER.warn("TaskRun (" + task.getId() + ") termination: " + e.getMessage());
    }
  }

  private static boolean isTransient(HttpClientErrorException e) {
    return e.getStatusCode().isSameCodeAs(HttpStatus.REQUEST_TIMEOUT)
        || e.getStatusCode().isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS);
  }

  private static String retrying(String id, int attempt, Duration delay, Exception e) {
    return "TaskRun ("
        + id
        + ") end attempt "
        + attempt
        + " failed, retrying in "
        + delay.toMillis()
        + " ms: "
        + e.getMessage();
  }

  private static TaskRunEndRequest succeeded(TaskResult result) {
    TaskResult outcome = (result != null) ? result : TaskResult.empty();
    TaskRunEndRequest end = new TaskRunEndRequest();
    end.setStatus(RunStatus.succeeded);
    end.setStatusMessage(outcome.getMessage());
    end.setResults(outcome.getResults());
    return end;
  }

  private static TaskRunEndRequest failed(
      String statusReason, String message, List<RunResult> results) {
    TaskRunEndRequest end = new TaskRunEndRequest();
    end.setStatus(RunStatus.failed);
    end.setStatusReason(statusReason);
    end.setStatusMessage(message);
    end.setResults(results);
    return end;
  }
}
