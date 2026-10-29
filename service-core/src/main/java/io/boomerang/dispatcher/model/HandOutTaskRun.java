package io.boomerang.dispatcher.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.model.RunRetry;
import io.boomerang.common.model.TaskRun;
import org.springframework.beans.BeanUtils;

/**
 * A task run on its way to a dispatcher: the public {@link TaskRun} plus the entity's retry state,
 * which the public model never carries and {@link DispatchTaskRun#of} puts on the wire. Built only
 * by the dispatcher service, for the task poll and the start and end answers.
 */
public class HandOutTaskRun extends TaskRun {

  @JsonIgnore private final RunRetry retry;

  public HandOutTaskRun(TaskRunEntity entity) {
    super(entity);
    this.retry = entity.getRetry();
  }

  /** The public model an engine transition answered, with the retry state read beside it. */
  public HandOutTaskRun(TaskRun model, RunRetry retry) {
    BeanUtils.copyProperties(model, this);
    this.retry = retry;
  }

  public RunRetry getRetry() {
    return retry;
  }
}
