package io.boomerang.dispatcher;

import io.boomerang.dispatcher.model.TaskResponse;
import io.boomerang.common.enums.TaskDeletion;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.TaskRun;
import io.boomerang.error.TaskExecutionException;
import io.boomerang.executor.TaskExecutor;
import io.boomerang.executor.TaskImageResolver;
import io.boomerang.kube.exception.KubeRuntimeException;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class TaskService {

  private static final Logger LOGGER = LogManager.getLogger(TaskService.class);

  // The runtime object is deleted once the Task has already finished, so the grace before the
  // delete is time no caller should be made to wait for.
  private static final long DELETE_GRACE_MS = 1000;

  @Value("${kube.task.deletion}")
  private TaskDeletion taskDeletion;

  @Value("${kube.task.timeout}")
  private Long taskTimeout;

  private final TaskExecutor executor;

  private final TaskImageResolver imageResolver;

  // Proxy to self so the delete goes through the @Async proxy and hops threads; a plain self-call
  // is not intercepted and would run the delete, grace included, on the dispatch thread.
  @Autowired @Lazy private TaskService self;

  public TaskService(TaskExecutor executor, TaskImageResolver imageResolver) {
    this.executor = executor;
    this.imageResolver = imageResolver;
    LOGGER.info("Task executor: " + executor.getClass().getSimpleName());
  }

  protected TaskDeletion getTaskDeletion(TaskDeletion deletion) {
    return deletion != null ? deletion : taskDeletion;
  }

  protected Long getTaskTimeout(Long timeout) {
    return timeout != null && timeout != 0 ? timeout : taskTimeout;
  }

  public TaskResponse terminate(TaskRun task) {
    TaskResponse response =
        new TaskResponse("0", "Task (" + task.getId() + ") is meant to be terminated now.", null);

    executor.cancel(task);

    return response;
  }

  public TaskResponse execute(TaskRun task) {
    TaskResponse response =
        new TaskResponse("0", "Task (" + task.getId() + ") has been executed successfully.", null);
    List<RunResult> results = new ArrayList<>();
    // Resolved, not read off the spec: an `ai` task carries no image of its own and the
    // dispatcher supplies the Flow-shipped worker image for it (TaskImageResolver).
    if (imageResolver.image(task) == null) {
      throw new TaskExecutionException("DispatchError", "NO_TASK_IMAGE - " + task.getClass().toString());
    } else {
      Long timeout = getTaskTimeout(task.getTimeout());
      try {
        executor.create(task, timeout);
        results = executor.watch(task, timeout);
        if (getTaskDeletion(task.getSpec().getDeletion()).equals(TaskDeletion.OnSuccess)) {
          // This will only delete on success as failure throws an Exception.
          self.deleteTaskRun(task);
        }
      } catch (KubernetesClientException e) {
        // KubernetesClientException handles the case where an internal admission
        // controller rejects the creation
        if (e.getMessage().contains("admission webhook")) {
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
        response.setResults(results);
        if (getTaskDeletion(task.getSpec().getDeletion()).equals(TaskDeletion.Always)) {
          self.deleteTaskRun(task);
        }
        LOGGER.info("Task (" + task.getId() + ") has completed with code " + response.getCode());
      }
    }
    return response;
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
