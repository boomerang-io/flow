package io.boomerang.common.model;

import java.util.Map;
import lombok.Data;

@Data
public class WorkflowCount {

  private Map<String, Long> status;
}
