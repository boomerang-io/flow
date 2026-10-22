package io.boomerang.error;

import java.util.Date;

public class RestErrorResponse {
  
  private Date timestamp = new Date();
  private int code;
  private String reason;
  private String message;
  private String status;
  private String cause;
  
  public int getCode() {
    return code;
  }
  public void setCode(int code) {
    this.code = code;
  }
  public String getReason() {
    return reason;
  }
  public void setReason(String reason) {
    this.reason = reason;
  }
  public String getMessage() {
    return message;
  }
  public void setMessage(String message) {
    this.message = message;
  }
  public String getStatus() {
    return status;
  }
  public void setStatus(String status) {
    this.status = status;
  }
  public Date getTimestamp() {
    return timestamp;
  }
  public void setTimestamp(Date timestamp) {
    this.timestamp = timestamp;
  }
  public String getCause() {
    return cause;
  }
  public void setCause(String cause) {
    this.cause = cause;
  }
}
