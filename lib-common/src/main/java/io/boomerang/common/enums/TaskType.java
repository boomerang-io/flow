package io.boomerang.common.enums;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/*
 * TaskTypes map to Tasks and also determine the logic gates for TaskExecution.
 *
 * If new TaskTypes are added, additional logic is needed in TaskExecutionServiceImpl
 *
 * If TaskTypes are altered, logic will need to be checked in TaskExecutionServiceImpl
 */
public enum TaskType {
  start("start"),
  end("end"),
  template("template"),
  custom("custom"),
  generic("generic"),
  decision("decision"), // NOSONAR
  approval("approval"),
  setwfproperty("setwfproperty"),
  manual("manual"),
  eventwait("eventwait"),
  acquirelock("acquirelock"), // NOSONAR
  releaselock("releaselock"),
  runworkflow("runworkflow"),
  runscheduledworkflow("runscheduledworkflow"),
  script("script"), // NOSONAR
  setwfstatus("setwfstatus"),
  sleep("sleep"); // NOSONAR

  private String label;

  TaskType(String label) {
    this.label = label;
  }

  @JsonValue
  public String getLabel() {
    return label;
  }

  public static TaskType getType(String type) {
    return Arrays.asList(TaskType.values()).stream()
        .filter(value -> value.getLabel().equals(type))
        .findFirst()
        .orElse(null);
  }

  public static List<TaskType> convertToTaskTypeList(List<String> types) {
    return types.stream()
        .map(TaskType::getType) // Convert each String to TaskType
        .filter(taskType -> taskType != null) // Exclude null values
        .collect(Collectors.toList()); // Collect into a List<TaskType>
  }
}
