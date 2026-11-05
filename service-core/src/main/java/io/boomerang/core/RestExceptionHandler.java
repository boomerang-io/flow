package io.boomerang.core;

import java.lang.annotation.Annotation;
import java.util.Locale;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.request.WebRequest;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.error.RestErrorResponse;
import io.boomerang.common.enums.ParamType;
import io.boomerang.common.validation.ParamName;
import io.boomerang.common.validation.ResourceName;
import io.boomerang.core.security.FlowAuthenticationException;
import jakarta.validation.ConstraintViolation;
import tools.jackson.databind.exc.InvalidFormatException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@Order(Ordered.HIGHEST_PRECEDENCE)
@ControllerAdvice
public class RestExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger LOGGER = LogManager.getLogger();

  @Value("${flow.error.include-cause:false}")
  public boolean includeCause;

  @Autowired
  private MessageSource messageSource;

  @ExceptionHandler({BoomerangException.class})
  public ResponseEntity<Object> handleBoomerangException(BoomerangException ex) {

    RestErrorResponse errorResponse = new RestErrorResponse();
    errorResponse.setCode(ex.getCode());
    errorResponse.setReason(ex.getReason());
    if (ex.getMessage() == null || ex.getMessage().isBlank()) {
      try {
        errorResponse
            .setMessage(messageSource.getMessage(ex.getReason(), ex.getArgs(), Locale.ENGLISH));
      } catch (NoSuchMessageException nsme) {
        errorResponse.setMessage("No message available");
      }
    } else {
      errorResponse.setMessage(ex.getMessage());
    }
    errorResponse.setStatus(ex.getStatus().toString());
    if (includeCause && ex.getCause() != null) {
      errorResponse.setCause(ex.getCause().toString());
    }

    return new ResponseEntity<>(errorResponse, new HttpHeaders(), ex.getStatus());
  }

  /*
   * Routes a @Valid @RequestBody failure through the same RestErrorResponse shape as every other
   * platform error, rather than Spring's default field-error body. Only the first field error is
   * reported - the platform error format carries one code/reason/message, not a list.
   */
  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    FieldError first = ex.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
    if (first == null) {
      return handleBoomerangException(
          new BoomerangException(BoomerangError.QUERY_INVALID_FILTERS, "body"));
    }
    return handleBoomerangException(new BoomerangException(errorFor(first), first.getRejectedValue()));
  }

  /*
   * A parameter type Jackson cannot read (say "type": "password" on a run request) answers in the
   * platform shape as PARAM_INVALID_TYPE rather than silently becoming a string or failing with
   * Spring's default body. Any other unreadable body keeps the framework's handling.
   */
  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
      if (cause instanceof InvalidFormatException invalid
          && ParamType.class.equals(invalid.getTargetType())) {
        return handleBoomerangException(
            new BoomerangException(
                BoomerangError.PARAM_INVALID_TYPE,
                invalid.getValue(),
                "string, array, object or secret",
                ParamType.string.name()));
      }
    }
    return super.handleHttpMessageNotReadable(ex, headers, status, request);
  }

  private static BoomerangError errorFor(FieldError fieldError) {
    try {
      Annotation constraint =
          fieldError.unwrap(ConstraintViolation.class).getConstraintDescriptor().getAnnotation();
      if (constraint instanceof ResourceName resourceName) {
        return resourceName.error();
      }
      if (constraint instanceof ParamName) {
        return BoomerangError.PARAM_INVALID_NAME;
      }
    } catch (IllegalArgumentException notAConstraintViolation) {
      // Not a bean-validation constraint violation (e.g. a binding/conversion error) - fall
      // through to the generic reason.
    }
    return BoomerangError.QUERY_INVALID_FILTERS;
  }

  /*
   * Renders Spring Security's AuthenticationException (raised via the delegatedAuthenticationEntryPoint
   * bean - see DelegatedAuthenticationEntryPoint/AuthenticationFilter) as the platform's standard
   * RestErrorResponse body instead of the framework's default error page,
   * with a distinguishing code/reason and, on a 401, a WWW-Authenticate header. FlowAuthenticationException carries the
   * specific BoomerangError; any other AuthenticationException (e.g. thrown directly by Spring Security
   * machinery) falls back to the generic AUTH_REQUIRED shape.
   */
  @ExceptionHandler({AuthenticationException.class})
  @ResponseBody
  public ResponseEntity<RestErrorResponse> handleAuthenticationException(AuthenticationException ex) {
    BoomerangError error =
        ex instanceof FlowAuthenticationException flowEx ? flowEx.getError() : BoomerangError.AUTH_REQUIRED;

    RestErrorResponse re = new RestErrorResponse();
    re.setCode(error.getCode());
    re.setReason(error.getReason());
    if (ex.getMessage() != null && !ex.getMessage().isBlank()) {
      re.setMessage(ex.getMessage());
    } else {
      try {
        re.setMessage(messageSource.getMessage(error.getReason(), null, Locale.ENGLISH));
      } catch (NoSuchMessageException nsme) {
        re.setMessage("Authentication failed.");
      }
    }
    re.setStatus(error.getStatus().toString());
    if (includeCause && ex.getCause() != null) {
      re.setCause(ex.getCause().toString());
    }

    HttpHeaders headers = new HttpHeaders();
    if (error.getStatus() == HttpStatus.UNAUTHORIZED) {
      headers.add(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    }
    return new ResponseEntity<>(re, headers, error.getStatus());
  }

  /*
   * A permission the caller does not hold, denied by method security at the endpoint, stays a 403
   * and answers in the platform shape with PERMISSION_DENIED's code, reason and message.
   */
  @ExceptionHandler({AccessDeniedException.class})
  public ResponseEntity<Object> handleAccessDeniedException(
      AccessDeniedException ex, WebRequest request) {
    RestErrorResponse re = errorResponse(BoomerangError.PERMISSION_DENIED, HttpStatus.FORBIDDEN);
    return handleExceptionInternal(ex, re, new HttpHeaders(), HttpStatus.FORBIDDEN, request);
  }

  /*
   * Anything no other handler maps is a 500 with a generic message: the exception is logged here
   * and never sent, so its message and stack stay on the server.
   */
  @ExceptionHandler({Exception.class})
  public ResponseEntity<Object> handleUnexpectedException(Exception ex, WebRequest request) {
    LOGGER.error("Unhandled error answering {}", request.getDescription(false), ex);
    RestErrorResponse re =
        errorResponse(BoomerangError.INTERNAL_SERVER_ERROR, HttpStatus.INTERNAL_SERVER_ERROR);
    if (includeCause && ex.getCause() != null) {
      re.setCause(ex.getCause().toString());
    }
    return handleExceptionInternal(
        ex, re, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
  }

  /*
   * Every framework error the base class maps (an unsupported method or media type, a missing
   * parameter, an unknown route) keeps its status and headers and answers in the platform shape:
   * the HTTP status as code and reason, as the codes below 1000 do, and Spring's detail as the
   * message.
   */
  @Override
  protected ResponseEntity<Object> createResponseEntity(
      Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
    if (body instanceof RestErrorResponse) {
      return new ResponseEntity<>(body, headers, statusCode);
    }
    HttpStatus status = HttpStatus.resolve(statusCode.value());
    RestErrorResponse re = new RestErrorResponse();
    re.setCode(statusCode.value());
    re.setReason((status != null) ? status.name() : String.valueOf(statusCode.value()));
    String detail = (body instanceof ProblemDetail problem) ? problem.getDetail() : null;
    re.setMessage(
        (detail != null) ? detail : ((status != null) ? status.getReasonPhrase() : null));
    re.setStatus(statusCode.toString());
    return new ResponseEntity<>(re, headers, statusCode);
  }

  // The error's code, reason and message, answered at the given status.
  private RestErrorResponse errorResponse(BoomerangError error, HttpStatus status) {
    RestErrorResponse re = new RestErrorResponse();
    re.setCode(error.getCode());
    re.setReason(error.getReason());
    re.setMessage(messageSource.getMessage(error.getReason(), null, Locale.ENGLISH));
    re.setStatus(status.toString());
    return re;
  }
}
