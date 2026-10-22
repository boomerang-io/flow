package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.*;
import io.boomerang.common.enums.ParamType;
import java.util.HashMap;
import java.util.Map;
import lombok.Data;

@Data
public class ParamSpec {

  private String name;
  private ParamType type;
  private String description;

  @JsonProperty("default")
  private Object defaultValue;

  private Map<String, Object> unknownFields = new HashMap<>();

  @JsonAnyGetter
  @JsonPropertyOrder(alphabetic = true)
  public Map<String, Object> otherFields() {
    return unknownFields;
  }

  @JsonAnySetter
  public void setOtherField(String name, Object value) {
    unknownFields.put(name, value);
  }

  @Override
  public String toString() {
    return "ParamSpec [name="
        + name
        + ", type="
        + type
        + ", description="
        + description
        + ", defaultValue="
        + defaultValue
        + "]";
  }
}
