package io.boomerang.common.model;

import io.boomerang.common.enums.TriggerEnum;
import lombok.Data;

/*
 * Extended WorkflowRunSubmitRequest version for the Workflow service that includes triggerDetails
 */
@Data
public class WorkflowSubmitRequest extends WorkflowRunRequest {

  private Integer workflowVersion;
  private TriggerEnum trigger;
}
