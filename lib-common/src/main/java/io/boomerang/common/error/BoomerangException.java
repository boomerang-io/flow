package io.boomerang.error;

import org.springframework.http.HttpStatus;

/*
 * The Boomerang exception format
 * 
 * References:
 * - https://cloud.google.com/apis/design/errors
 * - https://docs.aws.amazon.com/AWSEC2/latest/APIReference/errors-overview.html#api-error-response
 */
public class BoomerangException extends RuntimeException {

  private static final long serialVersionUID = 1L;
  
  private final int code;
  private final String reason;
  private final String message;
  private final HttpStatus status;
  private final Object[] args;
  
  public BoomerangException(int code, String reason, HttpStatus status, Object... args) {
    super();
    this.code = code;
    this.reason = reason;
    this.message = null;
    this.status = status;
    this.args = args;
  }
  
  public BoomerangException(Throwable ex, int code, String reason, HttpStatus status, Object... args) {
    super(ex);
    this.code = code;
    this.reason = reason;
    this.message = null;
    this.status = status;
    this.args = args;
  }
  
  public BoomerangException(int code, String reason, String message, HttpStatus status, Object... args) {
    super();
    this.code = code;
    this.reason = reason;
    this.message = message;
    this.status = status;
    this.args = args;
  }
  
  public BoomerangException(Throwable ex, int code, String reason, String message, HttpStatus status, Object... args) {
    super(ex);
    this.code = code;
    this.reason = reason;
    this.message = message;
    this.status = status;
    this.args = args;
  }

  public BoomerangException(BoomerangError error, Object... args) {
    super();
    this.code = error.getCode();
    this.reason = error.getReason();
    this.message = "";
    this.status = error.getStatus();
    this.args = args;
  }

  public BoomerangException(Throwable ex, BoomerangError error, Object... args) {
    super(ex);
    this.code = error.getCode();
    this.reason = error.getReason();
    this.message = "";
    this.status = error.getStatus();
    this.args = args;
  }
  
  public int getCode() {
    return code;
  }

  public String getReason() {
    return reason;
  }

  public String getMessage() {
    return message;
  }

  public Object[] getArgs() {
      return args;
  }

  public HttpStatus getStatus() {
    return status;
  }

}
