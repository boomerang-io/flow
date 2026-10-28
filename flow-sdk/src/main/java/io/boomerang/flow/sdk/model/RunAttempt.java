package io.boomerang.flow.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * One attempt of a task run that a requeue superseded: how it ended and which dispatcher held it.
 * The task run's own status, reason, message and duration are the current attempt.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunAttempt(
    Integer number,
    Instant claimedAt,
    Instant endedAt,
    Long duration,
    RunStatus status,
    String statusReason,
    String statusMessage,
    String dispatcherRef) {}
