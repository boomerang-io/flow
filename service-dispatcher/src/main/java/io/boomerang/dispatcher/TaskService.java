package io.boomerang.dispatcher;

import io.boomerang.dispatcher.sdk.TaskContext;
import io.boomerang.dispatcher.sdk.TaskFailure;
import io.boomerang.dispatcher.sdk.TaskHandler;
import io.boomerang.dispatcher.sdk.TaskResult;
import io.boomerang.dispatcher.sdk.model.RunResult;
import io.boomerang.dispatcher.sdk.model.TaskDeletion;
import io.boomerang.dispatcher.sdk.model.TaskRun;
import io.boomerang.dispatcher.sdk.model.TaskType;
import io.boomerang.error.BoomerangException;
import io.boomerang.error.TaskExecutionException;
import io.boomerang.executor.TaskExecutor;
import io.boomerang.executor.TaskImageResolver;
import io.boomerang.kube.KubeLogService;
import io.boomerang.kube.exception.KubeRuntimeException;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * The container task handler: runs a task as a Kubernetes object through the configured {@link
 * TaskExecutor} and turns how it ended into the SDK's outcome. The SDK has already started the
 * task with the engine, renews its lease while this runs, and reports the end.
 */
@Service
public class TaskService implements TaskHandler {

  private static final Logger LOGGER = LogManager.getLogger(TaskService.class);

  // The runtime object is deleted once the Task has already finished, so the grace before the
  // delete is time no caller should be made to wait for.
  private static final long DELETE_GRACE_MS = 1000;

  @Value("${kube.task.deletion}")
  private TaskDeletion taskDeletion;

  @Value("${kube.task.timeout}")
  private Long taskTimeout;

  // Whether a finished task's pod log is stored with the engine, so it outlives the pod and is
  // served without this dispatcher. Off, or an engine with no artifact store, leaves the engine
  // reading this dispatcher's log route for as long as the pod exists.
  @Value("${flow.dispatcher.logs.upload:true}")
  private boolean uploadLogs;

  private final TaskExecutor executor;

  private final TaskImageResolver imageResolver;

  private final ObjectProvider<KubeLogService> logs;

  // Proxy to self so the delete goes through the @Async proxy and hops threads; a plain self-call
  // is not intercepted and would run the delete, grace included, on the dispatch thread.
  @Autowired @Lazy private TaskService self;

  public TaskService(TaskExecutor executor, TaskImageResolver imageResolver) {
    this(executor, imageResolver, null);
  }

  @Autowired
  public TaskService(
      TaskExecutor executor, TaskImageResolver imageResolver, ObjectProvider<KubeLogService> logs) {
    this.executor = executor;
    this.imageResolver = imageResolver;
    this.logs = logs;
    LOGGER.info("Task executor: " + executor.getClass().getSimpleName());
  }

  protected TaskDeletion getTaskDeletion(TaskDeletion deletion) {
    return deletion != null ? deletion : taskDeletion;
  }

  protected Long getTaskTimeout(Long timeout) {
    return timeout != null && timeout != 0 ? timeout : taskTimeout;
  }

  @Override
  public TaskResult run(TaskRun task, TaskContext context) {
    try {
      return TaskResult.of(
          execute(task, context), "Task (" + task.getId() + ") has been executed successfully.");
    } catch (TaskExecutionException e) {
      throw new TaskFailure(e.getStatusReason(), e.getMessage(), e.getResults());
    } catch (BoomerangException e) {
      throw new TaskFailure(TaskFailure.DISPATCH_ERROR, e.getMessage(), e);
    }
  }

  /** Cancel the task's runtime object; throws when there is none to cancel. */
  @Override
  public void cancel(TaskRun task) {
    executor.cancel(task);
  }

  /** {@link #execute(TaskRun, TaskContext)} with no context: nothing stores the log. */
  public List<RunResult> execute(TaskRun task) {
    return execute(task, null);
  }

  /**
   * Create the task's runtime object, wait for it to finish, store its log with the engine, and
   * return its results. Throws {@link TaskExecutionException} carrying the typed reason when it
   * failed or never started; the log is stored on that path too, since it is where the failure
   * shows.
   */
  public List<RunResult> execute(TaskRun task, TaskContext context) {
    List<RunResult> results = new ArrayList<>();
    // Resolved, not read off the spec, so an artifact task's worker image counts (TaskImageResolver).
    // Every other type, `ai` included, must declare its image.
    String image = imageResolver.image(task);
    if (image == null || image.isBlank()) {
      throw new TaskExecutionException(
          "DispatchError",
          "NO_TASK_IMAGE - task " + task.getName() + " (" + task.getId() + ") declares no image");
    }
    // An `ai` revision may only declare an image under an allowed prefix; anything else is
    // refused here, before anything is created, so the refusal is visible on the task.
    if (TaskType.ai.equals(task.getType()) && !imageResolver.allowedAiImage(image)) {
      throw new TaskExecutionException(
          "ImageNotAllowed",
          "IMAGE_NOT_ALLOWED - image '" + image + "' is not under flow.dispatcher.ai.image-prefixes");
    }
    Long timeout = getTaskTimeout(task.getTimeout());
    boolean succeeded = false;
    try {
      executor.create(task, timeout);
      results = executor.watch(task, timeout);
      succeeded = true;
    } catch (KubernetesClientException e) {
      // A namespace quota refusing the object is the cluster being full, not the task being
      // wrong: reported as ExceededQuota, which the engine requeues for another attempt.
      if (e.getCode() == 403 && e.getMessage() != null && e.getMessage().contains("exceeded quota")) {
        LOGGER.info(e.toString());
        throw new TaskExecutionException("ExceededQuota", "EXCEEDED_QUOTA - " + e.getMessage());
      }
      // KubernetesClientException handles the case where an internal admission
      // controller rejects the creation
      if (e.getMessage() != null && e.getMessage().contains("admission webhook")) {
        LOGGER.info(e.toString());
        throw new TaskExecutionException("AdmissionDenied", "ADMISSION_WEBHOOK_DENIED - " + e.getMessage());
      } else {
        throw new TaskExecutionException("DispatchError", e.toString());
      }
    } catch (KubeRuntimeException e) {
      LOGGER.info("DEBUG::Task Is Being Set as Failed");
      throw new TaskExecutionException("DispatchError", e.toString());
    } catch (InterruptedException e) {
      throw new TaskExecutionException("DispatchError", "TASK_CREATION_ERROR - " + e.getMessage());
    } catch (ParseException e) {
      throw new TaskExecutionException("DeadlineExceeded", "TASK_CREATION_TIMEOUT_ERROR - " + e.getMessage());
    } finally {
      // The pod holds the log, so it is read before the runtime object is deleted.
      storeLog(task, context);
      TaskDeletion deletion = getTaskDeletion(task.getSpec().getDeletion());
      if (deletion.equals(TaskDeletion.Always)
          || (succeeded && deletion.equals(TaskDeletion.OnSuccess))) {
        self.deleteTaskRun(task);
      }
      LOGGER.info("Task (" + task.getId() + ") has completed.");
    }
    return results;
  }

  // Best effort: a pod that never existed, or one whose log cannot be read, leaves the engine to
  // read the live route instead; it never fails the task.
  private void storeLog(TaskRun task, TaskContext context) {
    KubeLogService podLogs = (logs != null) ? logs.getIfAvailable() : null;
    if (!uploadLogs || context == null || podLogs == null) {
      return;
    }
    try {
      context.uploadLog(
          podLogs.getPodLog(task.getWorkflowRef(), task.getWorkflowRunRef(), task.getId(), null));
    } catch (RuntimeException e) {
      LOGGER.warn("Task (" + task.getId() + ") log not read from its pod: " + e.getMessage());
    }
  }

  /**
   * Delete the Task's runtime object, off the caller's thread. The grace before the delete keeps it
   * clear of the executor's own closing reads on the Job/TaskRun it has just watched to completion;
   * it is a fixed wait, not a signal that those reads are done.
   */
  @Async
  public void deleteTaskRun(TaskRun task) {
    try {
      Thread.sleep(DELETE_GRACE_MS);
    } catch (InterruptedException e) {
      // Still delete: an undeleted Job outlives the dispatcher and leaks cluster resources.
      Thread.currentThread().interrupt();
    }
    executor.delete(task);
  }
}
