package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.boomerang.common.enums.ExecutionCondition;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WorkflowTaskDependency {

  private String taskRef;
  private String decisionCondition;
  private ExecutionCondition executionCondition = ExecutionCondition.always;

  @Override
  public String toString() {
    return "TaskDependency [taskRef="
        + taskRef
        + ", decisionCondition="
        + decisionCondition
        + ", executionCondition="
        + executionCondition
        + "]";
  }
}
