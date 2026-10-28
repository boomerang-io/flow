package io.boomerang.dispatcher.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * One attempt of a task run that a requeue superseded, as the engine hands it out beside the
 * order for the next: how it ended and which dispatcher held it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RunAttempt {

  private Integer number;
  private Instant claimedAt;
  private Instant endedAt;
  private Long duration;
  private RunStatus status;
  private String statusReason;
  private String statusMessage;
  private String dispatcherRef;

  public Integer getNumber() {
    return number;
  }

  public void setNumber(Integer number) {
    this.number = number;
  }

  public Instant getClaimedAt() {
    return claimedAt;
  }

  public void setClaimedAt(Instant claimedAt) {
    this.claimedAt = claimedAt;
  }

  public Instant getEndedAt() {
    return endedAt;
  }

  public void setEndedAt(Instant endedAt) {
    this.endedAt = endedAt;
  }

  /** How long the attempt ran, in milliseconds. */
  public Long getDuration() {
    return duration;
  }

  public void setDuration(Long duration) {
    this.duration = duration;
  }

  public RunStatus getStatus() {
    return status;
  }

  public void setStatus(RunStatus status) {
    this.status = status;
  }

  public String getStatusReason() {
    return statusReason;
  }

  public void setStatusReason(String statusReason) {
    this.statusReason = statusReason;
  }

  public String getStatusMessage() {
    return statusMessage;
  }

  public void setStatusMessage(String statusMessage) {
    this.statusMessage = statusMessage;
  }

  public String getDispatcherRef() {
    return dispatcherRef;
  }

  public void setDispatcherRef(String dispatcherRef) {
    this.dispatcherRef = dispatcherRef;
  }

  @Override
  public String toString() {
    return "RunAttempt[number=" + number + ", status=" + status + ", statusReason=" + statusReason + "]";
  }
}
