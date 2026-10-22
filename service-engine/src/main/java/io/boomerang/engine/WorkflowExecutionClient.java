package io.boomerang.engine;

import org.springframework.stereotype.Service;
import io.boomerang.common.entity.WorkflowRunEntity;

/*
 * Internal client allowing for processing of individual workflows.
 *
 * Referenced by both the external WorkflowRun Controller and the internal Workflow Execution.
 *
 * TODO: determine if any of these can be async
 */
@Service
public class WorkflowExecutionClient {

  public void queue(
      WorkflowExecutionService workflowExecutionService, WorkflowRunEntity wfRunEntity) {
    workflowExecutionService.queue(wfRunEntity);
  }

  public void start(
      WorkflowExecutionService workflowExecutionService, WorkflowRunEntity wfRunEntity) {
    workflowExecutionService.start(wfRunEntity);
  }

  public void end(
      WorkflowExecutionService workflowExecutionService, WorkflowRunEntity wfRunEntity) {
    workflowExecutionService.end(wfRunEntity);
  }

  public void cancel(
      WorkflowExecutionService workflowExecutionService, WorkflowRunEntity wfRunEntity) {
    workflowExecutionService.cancel(wfRunEntity);
  }

  public void timeout(
      WorkflowExecutionService workflowExecutionService, WorkflowRunEntity wfRunEntity) {
    workflowExecutionService.timeout(wfRunEntity);
  }
}
