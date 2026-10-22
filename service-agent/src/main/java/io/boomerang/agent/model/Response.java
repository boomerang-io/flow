package io.boomerang.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Response {

  private String code;

  private String message;

  public Response() {
    // Do nothing
  }

  public Response(String code, String desc) {
    super();
    this.code = code;
    this.message = desc;
  }

  public String getMessage() {
    return this.message;
  }

  public String getCode() {
    return this.code;
  }

  public void setMessage(String desc) {
    this.message = desc;
  }

  public void setCode(String code) {
    this.code = code;
  }
}
