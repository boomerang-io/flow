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
 * internal-only entity fields (claim, timeoutAt, retry, waitUntil, dependencies, preApproved,
 * decisionValue, agentRef) never leak. Phase is retained - the task agent dispatches on it.
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
  private List<TaskWorkspace> workspaces = new LinkedList<>();
  private TaskRunSpec spec = new TaskRunSpec();
  private RunStatus status;
  private RunPhase phase;
  private String statusMessage;
  private String taskRef;
  private Integer taskVersion;
  private String workflowRef;
  private String workflowRevisionRef;
  private String workflowRunRef;
  private String workflowName;

  public TaskRun() {}

  public TaskRun(TaskRunEntity entity) {
    BeanUtils.copyProperties(entity, this);
  }
}
