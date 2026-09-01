package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.boomerang.common.validation.ParamName;
import java.util.List;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public class AbstractParam {

  //  private String key;
  @ParamName private String name;
  private String description;

  @JsonProperty("default")
  private Object defaultValue;

  private String label;
  private String type;
  private Integer minValueLength;
  private Integer maxValueLength;
  private List<KeyValuePair> options;
  private Boolean required;
  private String placeholder;
  private String helpertext;
  private String language;
  private Boolean disabled;
  private Object value;
  private boolean readOnly;
  private Boolean hiddenValue;
}
