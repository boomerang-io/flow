package io.boomerang.common.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.boomerang.common.model.*;
import java.util.LinkedList;
import java.util.List;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
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
@CompoundIndexes({
  @CompoundIndex(name = "workflow_ref_version_idx", def = "{'workflowRef': 1, 'version': 1}")
})
public class WorkflowRevisionEntity {
  @Id private String id;
  private Integer version;
  @Indexed private String workflowRef;
  private List<WorkflowTask> tasks = new LinkedList<>();
  private ChangeLog changelog;
  private String markdown;
  private List<AbstractParam> params;
  private List<WorkflowWorkspace> workspaces;
  private Long timeout;
  private Long retries;

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
        + ", workspaces="
        + workspaces
        + "]";
  }
}
