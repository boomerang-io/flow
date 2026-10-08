package io.boomerang.engine.model;

import io.boomerang.common.enums.TaskType;
import java.util.List;

/**
 * What a dispatcher's task poll may claim: the task types it asked for, always within the types it
 * registered, and - when the poll filtered on them - the task ids and workflow ids its task and
 * workflow-label filters resolved to. A null id list means not filtered; an empty one matches
 * nothing.
 */
public record ClaimFilter(List<TaskType> types, List<String> taskRefs, List<String> workflowRefs) {

  public static ClaimFilter of(List<TaskType> types) {
    return new ClaimFilter(types, null, null);
  }

  /** Whether no TaskRun can match, so the poll need not look. */
  public boolean matchesNothing() {
    return types.isEmpty()
        || (taskRefs != null && taskRefs.isEmpty())
        || (workflowRefs != null && workflowRefs.isEmpty());
  }
}
