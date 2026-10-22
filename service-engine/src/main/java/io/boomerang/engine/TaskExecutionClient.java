package io.boomerang.engine;

import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.entity.WorkflowRunEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/*
 * Internal client allowing for async processing of individual tasks.
 *
 * Referenced by both the external TaskRun Controller and the internal Workflow Execution.
 *
 * Ref: https://www.baeldung.com/spring-async
 */
@Service
public class TaskExecutionClient {

  @Autowired
  @Lazy
  @Qualifier("asyncTaskExecutor")
  TaskExecutor asyncTaskExecutor;

  @Async("asyncTaskExecutor")
  public void queue(TaskExecutionService taskService, TaskRunEntity taskRequest) {
    taskService.queue(taskRequest);
  }

  /*
   * This method is not asynchronous as it needs to respond to the external API in a synchronous manner.
   *
   *  Execute holds the code that needs to be asynchronous
   */
  public void start(TaskExecutionService taskService, TaskRunEntity taskRequest) {
    taskService.start(taskRequest);
  }

  @Async("asyncTaskExecutor")
  public void execute(
      TaskExecutionService taskService, TaskRunEntity taskRequest, WorkflowRunEntity wfRunEntity) {
    taskService.execute(taskRequest, wfRunEntity);
  }

  @Async("asyncTaskExecutor")
  public void end(TaskExecutionService taskService, TaskRunEntity taskResponse) {
    taskService.end(taskResponse);
  }
}
