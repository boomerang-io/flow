package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.RunStatus;
import java.util.Date;
import lombok.Data;

/**
 * One attempt of a task run that a requeue superseded: how it ended and who held it. The task
 * run's own status, reason, message and duration are the current attempt, so a run with no
 * attempts recorded is on its first.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RunAttempt {

  private int number;
  private Date claimedAt;
  private Date endedAt;
  private long duration;
  private RunStatus status;
  private String statusReason;
  private String statusMessage;
  private String dispatcherRef;

  /** The attempt the task run is on, ending now with the given outcome. */
  public static RunAttempt ended(
      TaskRunEntity taskRun, RunStatus status, String statusReason, String statusMessage) {
    Date now = new Date();
    RunAttempt attempt = new RunAttempt();
    attempt.setNumber((taskRun.getAttempts() != null ? taskRun.getAttempts().size() : 0) + 1);
    attempt.setClaimedAt(taskRun.getClaim() != null ? taskRun.getClaim().getAt() : null);
    attempt.setDispatcherRef(taskRun.getClaim() != null ? taskRun.getClaim().getBy() : null);
    attempt.setEndedAt(now);
    Date from = (taskRun.getStartTime() != null) ? taskRun.getStartTime() : attempt.getClaimedAt();
    attempt.setDuration(from != null ? now.getTime() - from.getTime() : 0);
    attempt.setStatus(status);
    attempt.setStatusReason(statusReason);
    attempt.setStatusMessage(statusMessage);
    return attempt;
  }
}
