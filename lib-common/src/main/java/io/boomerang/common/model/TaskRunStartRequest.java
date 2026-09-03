package io.boomerang.common.model;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
public class TaskRunStartRequest {

  private Map<String, String> labels = new HashMap<>();
  private Map<String, Object> annotations = new HashMap<>();
  private List<RunParam> params = new LinkedList<>();
  private Map<String, String> workspaces;
  private Long timeout;
  private boolean preApproved;

  /**
   * The registered id of the dispatcher making the request - the value the engine wrote to {@code
   * claim.by} when it claimed the TaskRun. The engine fences on it: a request from a dispatcher
   * that no longer holds the claim is rejected instead of overwriting the current claimant's
   * record. Absent on the legacy protocol, which is accepted unfenced.
   */
  private String dispatcherRef;
}
