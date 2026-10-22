package io.boomerang.workflow.model;

import io.boomerang.common.enums.RunStatus;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import io.boomerang.common.model.RunResult;
import lombok.Data;

@Data
public class WorkflowRunEventRequest {

  private Map<String, String> labels = new HashMap<>();

  private Map<String, Object> annotations = new HashMap<>();

  private List<RunResult> results = new LinkedList<>();

  private RunStatus status = RunStatus.succeeded;

  private String topic;
}
