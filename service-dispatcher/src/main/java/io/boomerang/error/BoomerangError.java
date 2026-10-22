package io.boomerang.error;

import java.text.MessageFormat;
import org.springframework.http.HttpStatus;

public enum BoomerangError {
  
  /** Add reusable error list here. */
  PVC_CREATE_CONDITION_NOT_MET(100, "Unable to create PVC with a status of Bound or Pending within {0} seconds.", HttpStatus.INTERNAL_SERVER_ERROR),
  TASK_EXECUTION_ERROR(100, "Unable to execute job with reason: {0}.", HttpStatus.INTERNAL_SERVER_ERROR);

  private final int code;
  private final String description;
  private final HttpStatus httpStatus;


  public int getCode() {
    return code;
  }

  public String getDescription(Object... args) {
    return MessageFormat.format(description, args);
  }


  public HttpStatus getHttpStatus() {
    return httpStatus;
  }

  private BoomerangError(int code, String description, HttpStatus httpStatus) {
    this.code = code;
    this.description = description;
    this.httpStatus = httpStatus;
  }
}
