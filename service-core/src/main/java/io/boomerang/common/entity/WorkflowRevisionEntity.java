package io.boomerang.common.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.boomerang.common.model.*;
import java.util.LinkedList;
import java.util.List;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/*
 * Workflow Revision Entity stores the detail for each version of the workflow in conjunction with Workflow Entity
 *
 * A number of these elements are relied on by the Workflow model
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
@Document(collection = "#{@mongoConfiguration.fullCollectionName('workflow_revisions')}")
public class WorkflowRevisionEntity {
  @Id private String id;
  private Integer version;
  private String workflowRef;
  private List<WorkflowTask> tasks = new LinkedList<>();
  private ChangeLog changelog;
  private String markdown;
  private List<AbstractParam> params;
  private WorkflowWorkspace workspace;
  /**
   * Set by the v3 upgrade on a revision that declared the retired Workflow storage. Submit refuses
   * such a revision; any save creates a revision without the flag.
   */
  private Boolean legacyWorkflowStorage;
  private Long timeout;
  private Long retries;
  private WorkflowConcurrency concurrency;

  @Override
  public String toString() {
    return "WorkflowRevisionEntity [id="
        + id
        + ", version="
        + version
        + ", workflowRef="
        + workflowRef
        + ", tasks="
        + tasks
        + ", changelog="
        + changelog
        + ", markdown="
        + markdown
        + ", params="
        + params
        + ", workspace="
        + workspace
        + "]";
  }
}
