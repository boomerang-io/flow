package io.boomerang.common.model;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Dispatcher → engine: the runs whose workspace volumes a dispatcher still holds. The engine
 * answers with the subset whose run is finished, so the dispatcher can release those volumes. The
 * run record carries nothing about release - the cluster is the source of truth for what exists
 * and the engine is the source of truth for what is finished.
 */
@Data
public class WorkspaceReleaseQuery {
  /** Owners of run workspace volumes: released once the run is completed or gone. */
  private List<String> workflowRunRefs = new ArrayList<>();
}
