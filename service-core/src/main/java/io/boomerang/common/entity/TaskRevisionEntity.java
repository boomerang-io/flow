package io.boomerang.common.entity;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.boomerang.common.model.ChangeLog;
import io.boomerang.common.model.Task;
import io.boomerang.common.model.TaskSpec;
import lombok.Data;
import org.springframework.beans.BeanUtils;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/*
 * The versioned elements of a task
 *
 * Ref: https://docs.spring.io/spring-data/mongodb/reference/mongodb/mapping/document-references.html
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
@Document(collection = "#{@mongoConfiguration.fullCollectionName('task_revisions')}")
public class TaskRevisionEntity {

  @Id private String id;
  private String parentRef;
  private String displayName;
  private String description;
  private String category;
  private String icon;
  private Integer version;
  private ChangeLog changelog;
  private TaskSpec spec = new TaskSpec();

  public TaskRevisionEntity() {
    // Do nothing
  }

  public TaskRevisionEntity(Task task) {
    BeanUtils.copyProperties(task, this, "id", "parentRef");
  }
}
