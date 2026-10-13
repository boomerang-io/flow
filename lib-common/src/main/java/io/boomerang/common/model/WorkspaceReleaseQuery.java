package io.boomerang.common.model;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Dispatcher → engine: the owners of the workspace volumes a dispatcher still holds, grouped by
 * storage type. The engine answers with the subset whose owner is finished, so the dispatcher
 * can release those volumes. The run record carries nothing about release - the cluster is the
 * source of truth for what exists and the engine is the source of truth for what is finished.
 */
@Data
public class WorkspaceReleaseQuery {
  /** Owners of {@code workflowrun}-scoped volumes: released once the run is completed or gone. */
  private List<String> workflowRunRefs = new ArrayList<>();

  /** Owners of {@code workflow}-scoped volumes: released once the workflow is deleted or gone. */
  private List<String> workflowRefs = new ArrayList<>();
}
