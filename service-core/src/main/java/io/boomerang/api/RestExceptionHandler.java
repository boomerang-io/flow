package io.boomerang.api;

import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import io.boomerang.common.error.BoomerangError;
import io.boomerang.common.error.BoomerangException;
import io.boomerang.common.error.RestErrorResponse;
import io.boomerang.core.security.FlowAuthenticationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@Order(Ordered.HIGHEST_PRECEDENCE)
@ControllerAdvice
public class RestExceptionHandler extends ResponseEntityExceptionHandler {

  // private static final Logger LOGGER =
  // LogManager.getLogger(ResponseEntityExceptionHandler.class);

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

    // LOGGER.error("Exception["+errorResponse.getCode()+"] " + errorResponse.getReason() + " - " +
    // errorResponse.getMessage());
    // LOGGER.error(ExceptionUtils.getStackTrace(ex));

    return new ResponseEntity<>(errorResponse, new HttpHeaders(), ex.getStatus());
  }

  /*
   * Renders Spring Security's AuthenticationException (raised via the delegatedAuthenticationEntryPoint
   * bean - see DelegatedAuthenticationEntryPoint/AuthenticationFilter) as the platform's standard
   * RestErrorResponse body instead of the framework's default error page (specifications/authentication.md §5),
   * with a distinguishing code/reason and a WWW-Authenticate header. FlowAuthenticationException carries the
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
    re.setMessage(ex.getMessage() != null && !ex.getMessage().isBlank() ? ex.getMessage() : "Authentication failed.");
    re.setStatus(error.getStatus().toString());
    if (includeCause && ex.getCause() != null) {
      re.setCause(ex.getCause().toString());
    }

    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    return new ResponseEntity<>(re, headers, error.getStatus());
  }
}
