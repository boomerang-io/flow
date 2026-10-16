package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.boomerang.common.enums.ParamType;
import lombok.Data;

@Data
public class RunParam {

  private String name;
  private Object value;
  // On the wire since the secret type: a consumer and the dispatcher need to know a value is a
  // secret. Absent means string, as it always has (ParameterManager). An unknown value is
  // rejected by Jackson and answered as PARAM_INVALID_TYPE (RestExceptionHandler).
  @JsonInclude(Include.NON_NULL)
  private ParamType type;

  protected RunParam() {}

  public RunParam(String name, Object value) {
    this.name = name;
    this.value = value;
  }

  public RunParam(String name, Object value, ParamType type) {
    this.name = name;
    this.type = type;
    this.value = value;
  }

  @Override
  public String toString() {
    return "RunParam [name=" + name + ", type=" + type + ", value=" + value + "]";
  }
}
