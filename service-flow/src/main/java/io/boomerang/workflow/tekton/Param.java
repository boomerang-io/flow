// package io.boomerang.workflow.tekton;
//
// import com.fasterxml.jackson.annotation.JsonAnyGetter;
// import com.fasterxml.jackson.annotation.JsonAnySetter;
// import com.fasterxml.jackson.annotation.JsonProperty;
// import com.fasterxml.jackson.annotation.JsonPropertyOrder;
// import io.boomerang.common.enums.ParamType;
// import java.util.HashMap;
// import java.util.Map;
// import lombok.Data;
//
// @Data
// public class Param {
//  private String name;
//  private ParamType type;
//  private String description;
//
//  @JsonProperty("default")
//  private Object defaultValue;
//
//  private Map<String, Object> unknownFields = new HashMap<>();
//
//  @JsonAnyGetter
//  @JsonPropertyOrder(alphabetic = true)
//  public Map<String, Object> otherFields() {
//    return unknownFields;
//  }
//
//  @JsonAnySetter
//  public void setOtherField(String name, Object value) {
//    unknownFields.put(name, value);
//  }
// }
