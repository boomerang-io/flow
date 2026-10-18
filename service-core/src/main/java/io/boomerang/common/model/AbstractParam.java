package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.boomerang.common.enums.ConfigType;
import io.boomerang.common.util.DataAdapterUtil;
import io.boomerang.common.validation.ParamName;
import java.util.List;
import lombok.Data;
import lombok.ToString;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public class AbstractParam {

  //  private String key;
  @ParamName private String name;
  private String description;

  @JsonProperty("default")
  @ToString.Exclude
  private Object defaultValue;

  private String label;
  private String type;
  private Integer minValueLength;
  private Integer maxValueLength;
  private List<KeyValuePair> options;

  // Numeric range for the slider control, the counterpart of options for select. Nullable: a
  // param of any other type carries none, so no stored document changes.
  private Double min;
  private Double max;
  private Double step;
  private Boolean required;
  private String placeholder;
  private String helpertext;
  private String language;
  private Boolean disabled;
  @ToString.Exclude private Object value;
  private boolean readOnly;
  private Boolean hiddenValue;

  // toString prints a `password` field's default and value as the redaction marker, so a logged
  // param never carries a secret; any other field prints its values for debugging.
  @ToString.Include(name = "defaultValue")
  private Object loggableDefaultValue() {
    return isPasswordField() && defaultValue != null ? DataAdapterUtil.REDACTED : defaultValue;
  }

  @ToString.Include(name = "value")
  private Object loggableValue() {
    return isPasswordField() && value != null ? DataAdapterUtil.REDACTED : value;
  }

  private boolean isPasswordField() {
    return ConfigType.PASSWORD.equals(ConfigType.getConfigType(type));
  }
}
