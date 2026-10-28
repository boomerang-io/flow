package io.boomerang.common.model;

import io.boomerang.common.enums.RunStatus;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
public class TaskRunEndRequest {

  private RunStatus status;

  private Map<String, String> labels = new HashMap<>();

  private Map<String, Object> annotations = new HashMap<>();

  private List<RunResult> results = new LinkedList<>();

  private String statusMessage;

  /**
   * Typed cause paired with {@code status}/{@code statusMessage}, for the engine to decide on and
   * the UI to filter by; {@code statusMessage} stays the human-readable text. The known causes are
   * {@link StatusReason}'s; {@link StatusReason#REQUEUEABLE} mean the task never started, so the
   * engine requeues it while its retry budget lasts instead of failing it. Any other value is an
   * ordinary failure.
   */
  private String statusReason;

  /**
   * Whether a retry could change this failure, as the dispatcher that saw it judges: {@code false}
   * marks the failure final, so the engine never requeues it on any budget, whatever the reason or
   * the node's retry setting; absent or {@code true} leaves the decision to the engine's rules.
   */
  private Boolean retryable;

  /**
   * The registered id of the dispatcher making the request - the value the engine wrote to {@code
   * claim.by} when it claimed the TaskRun. The engine fences on it: a request from a dispatcher
   * that no longer holds the claim is rejected instead of overwriting the current claimant's
   * record. Absent on the legacy protocol, which is accepted unfenced.
   */
  private String dispatcherRef;
}
