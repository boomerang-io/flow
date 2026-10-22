package io.boomerang.flow.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * A manual or approval action a run waits on: the task run that raised it, its status, the
 * approvers it needs and the decisions recorded so far.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Action(
    String id,
    ActionType type,
    ActionStatus status,
    String instructions,
    Instant creationDate,
    String workflowRef,
    String workflowName,
    String workflowRunRef,
    String taskRunRef,
    String taskName,
    String workspaceName,
    Integer numberOfApprovers,
    Long approvalsRequired,
    Long numberOfApprovals,
    String approverGroupRef,
    List<Actioner> actioners) {}
