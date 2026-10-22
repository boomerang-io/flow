package io.boomerang.common.model;

import io.boomerang.common.entity.TaskRunEntity;
import lombok.Data;
import org.springframework.beans.BeanUtils;

/*
 * Based on TaskRunEntity
 */
@Data
public class TaskRun extends TaskRunEntity {

  private String workflowName;

  public TaskRun() {}

  public TaskRun(TaskRunEntity entity) {
    BeanUtils.copyProperties(entity, this);
  }

  @Override
  public String toString() {
    return "TaskRun [workflowName=" + workflowName + ", toString()=" + super.toString() + "]";
  }
}
