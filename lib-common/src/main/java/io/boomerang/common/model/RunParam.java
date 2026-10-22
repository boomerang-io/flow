package io.boomerang.common.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.boomerang.common.enums.ParamType;
import lombok.Data;

@Data
public class RunParam {

  private String name;
  private Object value;
  @JsonIgnore private ParamType type;

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
