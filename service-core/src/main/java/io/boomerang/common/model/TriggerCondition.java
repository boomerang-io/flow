package io.boomerang.common.model;

import io.boomerang.common.enums.TriggerConditionOperation;
import java.util.List;
import lombok.Data;

@Data
public class TriggerCondition {

  private String field;
  private TriggerConditionOperation operation;
  private String value;
  private List<String> values;

  @Override
  public String toString() {
    return "TriggerCondition [field="
        + field
        + ", operation="
        + operation
        + ", value="
        + value
        + ", values="
        + values
        + "]";
  }
}
