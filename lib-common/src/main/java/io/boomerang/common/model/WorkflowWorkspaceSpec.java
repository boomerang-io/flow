package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties
public class WorkflowWorkspaceSpec {

  private String accessMode;
  private String className;
  private String mountPath;
  private String size;
}
