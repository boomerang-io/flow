package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.boomerang.common.entity.TaskRunEntity;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.springframework.beans.BeanUtils;
import org.springframework.data.annotation.Id;

/*
 * Public API model based on TaskRunEntity.
 *
 * Standalone POJO (no longer extends the entity) so the public contract is explicit and the
 * internal-only entity fields (claim, timeoutAt, retry, waitUntil, dependencies, preApprovedStatus,
 * decisionValue) never leak. Phase is retained for the webapp; a dispatcher gets DispatchTaskRun.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskRun {

  @Id private String id;
  private TaskType type;
  private String name;
  private Map<String, String> labels = new HashMap<>();
  private Map<String, Object> annotations = new HashMap<>();
  private Date creationDate;
  private Date startTime;
  private long duration;
  private Long timeout;
  private List<RunParam> params = new LinkedList<>();
  private List<RunResult> results = new LinkedList<>();
  private WorkflowWorkspace workspace;
  private TaskRunSpec spec = new TaskRunSpec();
  private RunStatus status;
  private RunPhase phase;
  private String statusMessage;

  private String statusReason;
  private String taskRef;
  private Integer taskVersion;
  private String workflowRef;
  private String workflowRevisionRef;
  private String workflowRunRef;
  private String workflowName;
  // Set only on an item of a foreach task: the parent TaskRun's id and the item's position from 0.
  private String parentRef;
  private Integer index;
  // True on the parent of a foreach task, the run that stands for its items in the graph and never
  // runs itself; absent on every other TaskRun. The setting and its items stay on the entity.
  private Boolean foreach;
  // Set on a TaskRun a scoped retry carried over instead of executing: the origin TaskRun it
  // stands for, whose log and action records it shares.
  private String reusedFromRef;
  // The attempt a dispatcher holds, last held or is handed next: 1, plus one per requeue. Absent
  // on a task no dispatcher has claimed - an approval, a wait, a task a scoped retry reused.
  private Integer attempt;
  // The attempts a requeue superseded, oldest first; absent on a task on its first attempt.
  private List<RunAttempt> attempts;

  public TaskRun() {}

  public TaskRun(TaskRunEntity entity) {
    BeanUtils.copyProperties(entity, this);
    this.foreach = (entity.getForeach() != null) ? Boolean.TRUE : null;
    if (entity.getClaim() != null) {
      int superseded =
          (entity.getAttempts() != null)
              ? entity.getAttempts().size()
              : ((entity.getRetry() != null) ? entity.getRetry().getCount() : 0);
      this.attempt = superseded + 1;
    }
  }
}
