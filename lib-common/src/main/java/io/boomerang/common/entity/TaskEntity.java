package io.boomerang.common.entity;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import lombok.Data;
import org.springframework.beans.BeanUtils;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.boomerang.common.model.Task;
import io.boomerang.common.enums.TaskStatus;
import io.boomerang.common.enums.TaskType;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
@Document(collection = "#{@mongoConfiguration.fullCollectionName('tasks')}")
public class TaskEntity {

  @Id private String id;
  @Indexed private String name;
  private TaskType type;
  private TaskStatus status = TaskStatus.active;
  private Date creationDate = new Date();
  private boolean verified = false;
  private Map<String, String> labels = new HashMap<>();
  private Map<String, Object> annotations = new HashMap<>();

  public TaskEntity() {
    // Do nothing
  }

  public TaskEntity(Task task) {
    BeanUtils.copyProperties(task, this, "id", "creationDate", "verified");
  }
}
