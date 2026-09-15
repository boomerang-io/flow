package io.boomerang.workflow;

import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowTaskDependency;
import io.boomerang.core.SettingsService;
import io.boomerang.workspace.WorkspaceService;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

/**
 * Resolves the timeout a WorkflowRun is created with, in minutes.
 *
 * <p>Three rules, applied in order to every submit:
 *
 * <ol>
 *   <li><b>Default.</b> The request's timeout wins, else the revision's, else the run-duration
 *       quota itself - the workspace's own {@code maxWorkflowRunDuration} override when it has
 *       one, else the platform {@code workspaces}/{@code max.workflowrun.duration} setting. A run
 *       can no longer be created unguarded just because neither the caller nor the Workflow named
 *       a budget.
 *   <li><b>Floor.</b> The timeout must be at least the critical path of the revision's task
 *       budgets - the longest chain of explicit task timeouts through the graph. A run guard
 *       beneath the budget of the work it guards reaps healthy tasks.
 *   <li><b>Ceiling.</b> The workspace's maximum run duration clamps whatever survives. A ceiling
 *       below the floor leaves no valid value, so it is refused rather than silently clamped.
 * </ol>
 *
 * <p>A timeout of 0 means unguarded, and an unguarded run can never fire beneath a task, so it is
 * exempt from the floor. Only the task's own declared timeout counts toward the floor; the
 * operator's {@code boomerang.io/task-timeout} default is a ceiling on every task rather than a
 * budget any one task claims, so a workflow that declares nothing has a floor of 0.
 */
@Service
public class RunTimeoutPolicy {
  private static final Logger LOGGER = LogManager.getLogger();

  private static final String SOURCE_REQUEST = "requested run timeout";
  private static final String SOURCE_WORKFLOW = "Workflow's run timeout";
  private static final String SOURCE_DEFAULT = "default run timeout";
  private static final String SOURCE_QUOTA = "workspace maximum run duration";

  private final SettingsService settingsService;

  public RunTimeoutPolicy(SettingsService settingsService) {
    this.settingsService = settingsService;
  }

  /**
   * @param requested the submit request's timeout, null or 0 when the caller named none
   * @param revisionTimeout the Workflow revision's timeout, null or 0 when it declares none
   * @param tasks the revision's tasks, the graph the floor is measured over
   * @param ceiling the workspace's maximum run duration in minutes, 0 or less when no workspace
   *     owns the Workflow - which is also the default when nobody declares a timeout
   * @return the timeout to stamp on the run, in minutes
   * @throws BoomerangException {@code WORKFLOWRUN_TIMEOUT_TOO_SHORT} when no value satisfies both
   *     the floor and the ceiling
   */
  public long resolve(
      Long requested, Long revisionTimeout, List<WorkflowTask> tasks, long ceiling) {
    long floor = criticalPath(tasks);

    String source = SOURCE_REQUEST;
    long timeout = value(requested);
    if (timeout == 0) {
      source = SOURCE_WORKFLOW;
      timeout = value(revisionTimeout);
    }
    if (timeout == 0) {
      source = SOURCE_DEFAULT;
      timeout = defaultTimeout(ceiling);
    }

    if (timeout > 0 && timeout < floor) {
      throw new BoomerangException(
          BoomerangError.WORKFLOWRUN_TIMEOUT_TOO_SHORT, source, timeout, floor);
    }
    if (ceiling > 0 && ceiling < floor) {
      throw new BoomerangException(
          BoomerangError.WORKFLOWRUN_TIMEOUT_TOO_SHORT, SOURCE_QUOTA, ceiling, floor);
    }
    if (ceiling > 0 && (timeout == 0 || timeout > ceiling)) {
      timeout = ceiling;
    }
    return timeout;
  }

  /**
   * The longest chain of task timeouts through the graph, in minutes: the floor any run guard over
   * this revision has to clear. Tasks are linked by name through their dependencies; a task with
   * no declared timeout contributes 0 but still carries its predecessors' cost forward.
   */
  public long criticalPath(List<WorkflowTask> tasks) {
    if (tasks == null || tasks.isEmpty()) {
      return 0;
    }
    Map<String, WorkflowTask> byName = new HashMap<>();
    tasks.stream()
        .filter(t -> t != null && t.getName() != null)
        .forEach(t -> byName.put(t.getName(), t));

    Map<String, Long> longestTo = new HashMap<>();
    long longest = 0;
    for (WorkflowTask task : byName.values()) {
      longest = Math.max(longest, longestTo(task, byName, longestTo, new ArrayDeque<>()));
    }
    return longest;
  }

  // Memoised longest-path-to-here. The visiting stack only guards against a cycle a malformed
  // revision could carry - DAGUtility rejects one at execution, and this must not hang before it.
  private long longestTo(
      WorkflowTask task,
      Map<String, WorkflowTask> byName,
      Map<String, Long> memo,
      Deque<String> visiting) {
    Long cached = memo.get(task.getName());
    if (cached != null) {
      return cached;
    }
    if (visiting.contains(task.getName())) {
      return 0;
    }
    visiting.push(task.getName());
    long upstream = 0;
    if (task.getDependencies() != null) {
      for (WorkflowTaskDependency dependency : task.getDependencies()) {
        WorkflowTask predecessor =
            dependency == null ? null : byName.get(dependency.getTaskRef());
        if (predecessor != null) {
          upstream = Math.max(upstream, longestTo(predecessor, byName, memo, visiting));
        }
      }
    }
    visiting.pop();
    long total = upstream + value(task.getTimeout());
    memo.put(task.getName(), total);
    return total;
  }

  /**
   * The default for a run nobody declared a timeout for: the run-duration quota itself. The
   * workspace's own ceiling when it has one, and otherwise - a Workflow no workspace owns - the
   * platform setting every workspace quota starts from. An install missing that setting has no
   * quota to apply at all, so such a run stays unguarded rather than failing to submit.
   */
  private long defaultTimeout(long ceiling) {
    if (ceiling > 0) {
      return ceiling;
    }
    try {
      return Long.parseLong(
          settingsService
              .getSettingConfig(
                  WorkspaceService.WORKSPACES_SETTINGS_KEY,
                  WorkspaceService.QUOTA_MAX_WORKFLOWRUN_DURATION)
              .getValue());
    } catch (RuntimeException e) {
      LOGGER.warn("No {} quota settings; a run with no declared timeout stays unguarded.",
          WorkspaceService.WORKSPACES_SETTINGS_KEY);
      return 0;
    }
  }

  private static long value(Long timeout) {
    return Objects.isNull(timeout) || timeout < 0 ? 0 : timeout;
  }
}
