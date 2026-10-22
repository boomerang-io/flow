package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties
public class WorkflowWorkspace {

  private String name;
  private String description;
  private String type;
  private boolean optional = false;
  private Object spec;
}
