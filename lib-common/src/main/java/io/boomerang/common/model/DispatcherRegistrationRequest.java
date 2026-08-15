package io.boomerang.common.model;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class DispatcherRegistrationRequest {
  private String name;
  private Integer version = 1;
  private String host;
  private List<String> workflowAnnotations = new ArrayList<>();
  private List<String> taskAnnotations = new ArrayList<>();
  private List<String> taskTypes = new ArrayList<>();

  public DispatcherRegistrationRequest() {
    // Default constructor
  }

  public DispatcherRegistrationRequest(String name, String host, List<String> taskTypes) {
    this.name = name;
    this.host = host;
    this.taskTypes = taskTypes;
  }
}
