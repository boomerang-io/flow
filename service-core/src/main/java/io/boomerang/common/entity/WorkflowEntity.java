package io.boomerang.common.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.boomerang.common.enums.WorkflowStatus;
import io.boomerang.common.enums.WorkflowType;
import io.boomerang.common.model.WorkflowTemplateRef;
import io.boomerang.common.model.WorkflowTrigger;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
@Document(collection = "#{@mongoConfiguration.fullCollectionName('workflows')}")
public class WorkflowEntity {

  @Id private String id;
  private String name;
  private String displayName;
  private WorkflowStatus status = WorkflowStatus.active;
  private Date creationDate = new Date();
  private String icon;
  private String description;
  private Map<String, String> labels = new HashMap<>();
  private Map<String, Object> annotations = new HashMap<>();
  private WorkflowTrigger triggers;

  // What the definition is for; absent reads as a plain workflow. A template or a starter lives
  // only in the system workspace (WorkflowType).
  private WorkflowType type;

  // Set on a link: the template this workflow runs by reference, read-only, in its own workspace.
  private WorkflowTemplateRef templateRef;
}
