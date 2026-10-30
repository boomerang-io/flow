package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * One page of task runs read across workspaces, newest first, for a platform-wide grant. A row is
 * the task run's identity and lifecycle with the workspace it belongs to, never its params,
 * results or spec: the workspace's own task-run routes read those.
 */
@Data
@JsonInclude(Include.NON_NULL)
public class WorkflowRunInsightTaskRunPage {

  private List<TaskRunRow> content = new LinkedList<>();
  private int number;
  private int size;
  private long totalElements;
  private int totalPages;

  @Data
  @JsonInclude(Include.NON_NULL)
  public static class TaskRunRow {
    private String id;
    /** The workspace whose workflow the task run belongs to. */
    private String workspace;
    private String name;
    private TaskType type;
    private String taskRef;
    private RunStatus status;
    private RunPhase phase;
    private String statusReason;
    private Date creationDate;
    private Date startTime;
    private long duration;
    /** The current attempt, when a dispatcher has claimed the task run. */
    private Integer attempt;
    /** A for-each parent carries {@code true}; its items carry their {@code index}. */
    private Boolean foreach;
    private Integer index;
    private String workflowRef;
    private String workflowName;
    private String workflowRunRef;
    private Map<String, String> labels;
  }
}
