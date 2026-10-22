package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.boomerang.common.enums.TaskStatus;
import io.boomerang.common.enums.TaskType;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import lombok.Data;
import org.springframework.data.annotation.Id;

@Data
@JsonInclude(Include.NON_NULL)
public class Task {

  @Id private String id;
  private String name;
  private String displayName;
  private TaskType type;
  private Integer version;
  private TaskStatus status = TaskStatus.active;
  private Date creationDate = new Date();
  private boolean verified;
  private String description;
  private Map<String, String> labels = new HashMap<>();
  private Map<String, Object> annotations = new HashMap<>();
  private ChangeLog changelog;
  private String category;
  private TaskSpec spec = new TaskSpec();
  private String icon;

  @Override
  public String toString() {
    return "Task{"
        + "id='"
        + id
        + '\''
        + ", name='"
        + name
        + '\''
        + ", displayName='"
        + displayName
        + '\''
        + ", type="
        + type
        + ", version="
        + version
        + ", status="
        + status
        + ", creationDate="
        + creationDate
        + ", verified="
        + verified
        + ", description='"
        + description
        + '\''
        + ", labels="
        + labels
        + ", annotations="
        + annotations
        + ", changelog="
        + changelog
        + ", category='"
        + category
        + '\''
        + ", spec="
        + spec
        + ", icon='"
        + icon
        + '\''
        + '}';
  }
}
