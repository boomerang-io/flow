package io.boomerang.workflow.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.boomerang.common.enums.WorkflowStatus;
import java.util.Date;
import lombok.Data;

/*
 * Workflow Summary copies from Workflow to include in the Team response
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_EMPTY)
public class WorkflowSummary {

  private String id;
  private String name;
  private String displayName;
  private WorkflowStatus status = WorkflowStatus.active;
  private Integer version = 1;
  private Date creationDate = new Date();
  private String icon;
  private String description;
}
