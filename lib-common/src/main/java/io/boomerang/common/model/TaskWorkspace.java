package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties
public class TaskWorkspace {

  private String name;
  private String type;
  private boolean optional = false;
  private String mountPath;
}
