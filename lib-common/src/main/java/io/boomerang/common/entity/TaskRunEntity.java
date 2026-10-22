package io.boomerang.common.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.boomerang.common.enums.RunPhase;
import io.boomerang.common.enums.RunStatus;
import io.boomerang.common.enums.TaskType;
import io.boomerang.common.model.RunParam;
import io.boomerang.common.model.RunResult;
import io.boomerang.common.model.TaskRunSpec;
import io.boomerang.common.model.TaskWorkspace;
import io.boomerang.common.model.WorkflowTaskDependency;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@Document(collection = "#{@mongoConfiguration.fullCollectionName('task_runs')}")
@CompoundIndexes({
  @CompoundIndex(name = "status_phase_type_idx", def = "{'status': 1, 'phase': 1, 'type': 1}")
})
public class TaskRunEntity {

  @Id private String id;
  private TaskType type;
  private String name;
  private Map<String, String> labels = new HashMap<>();
  private Map<String, Object> annotations = new HashMap<>();
  private Date creationDate;
  private Date startTime;
  private long duration;
  private Long timeout;
  //  private Long retries;
  private List<RunParam> params = new LinkedList<>();
  private List<RunResult> results = new LinkedList<>();
  private List<TaskWorkspace> workspaces = new LinkedList<>();
  private TaskRunSpec spec = new TaskRunSpec();
  @Indexed private RunStatus status;
  @Indexed private RunPhase phase;
  private String statusMessage;
  @JsonIgnore private boolean preApproved;
  @JsonIgnore private String decisionValue;
  @JsonIgnore private List<WorkflowTaskDependency> dependencies;
  private String taskRef;
  private Integer taskVersion;
  private String workflowRef;
  private String workflowRevisionRef;
  @Indexed private String workflowRunRef; // Indexed when retrieving task runs for a workflow run
  private String agentRef;

  @Override
  public String toString() {
    return "TaskRunEntity [id="
        + id
        + ", type="
        + type
        + ", name="
        + name
        + ", labels="
        + labels
        + ", annotations="
        + annotations
        + ", creationDate="
        + creationDate
        + ", startTime="
        + startTime
        + ", params="
        + params
        + ", status="
        + status
        + ", phase="
        + phase
        + "]";
  }
}
