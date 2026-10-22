package io.boomerang.common.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.boomerang.common.model.AbstractParam;
import io.boomerang.common.model.ChangeLog;
import io.boomerang.common.model.WorkflowTask;
import io.boomerang.common.model.WorkflowWorkspace;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
@Document(collection = "#{@mongoConfiguration.fullCollectionName('workflow_templates')}")
public class WorkflowTemplateEntity {

  @Id @JsonIgnore private String id;
  @Indexed private String name;
  private String displayName;
  private Date creationDate = new Date();
  @Indexed private Integer version;
  private String icon;
  private String description;
  private String markdown;
  private Map<String, String> labels = new HashMap<>();
  private Map<String, Object> annotations = new HashMap<>();
  private List<WorkflowTask> tasks = new LinkedList<>();
  private ChangeLog changelog;
  private List<AbstractParam> params;
  private List<WorkflowWorkspace> workspaces;
  private Long timeout;
  private Long retries;
}
