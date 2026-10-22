// package io.boomerang.common.model;
//
// import com.fasterxml.jackson.annotation.JsonIgnore;
// import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
// import com.fasterxml.jackson.annotation.JsonInclude;
// import com.fasterxml.jackson.annotation.JsonInclude.Include;
// import java.util.List;
// import lombok.Data;
//
// @Data
// @JsonIgnoreProperties(ignoreUnknown = true)
// @JsonInclude(Include.NON_NULL)
// public class Config {
//
//  private String label;
//  private String type;
//  private Integer minValueLength;
//  private Integer maxValueLength;
//  private List<KeyValuePair> options;
//  private Boolean required;
//  private String placeholder;
//  private String helpertext;
//  private String language;
//  private Boolean disabled;
//  private String value;
//  private List<String> values;
//  private boolean readOnly;
//  private Boolean hiddenValue;
//
//  @JsonIgnore
//  public boolean getBooleanValue() {
//    if ("boolean".equals(this.getType())) {
//      return Boolean.parseBoolean(this.getValue());
//    } else {
//      throw new IllegalArgumentException("Configuration object is not of type boolean.");
//    }
//  }
// }
