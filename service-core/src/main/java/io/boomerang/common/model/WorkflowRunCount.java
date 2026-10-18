package io.boomerang.common.model;

import java.util.Map;
import lombok.Data;

@Data
public class WorkflowRunCount {

  private Map<String, Long> status;
}
