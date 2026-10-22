package io.boomerang.error;

import java.util.Locale;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@Order(Ordered.HIGHEST_PRECEDENCE)
@ControllerAdvice
public class RestExceptionHandler extends ResponseEntityExceptionHandler {
  
  private static final Logger LOGGER = LogManager.getLogger(ResponseEntityExceptionHandler.class);

  @Value("${flow.error.include-cause:false}")
  public boolean includeCause;

  @Autowired
  private MessageSource messageSource;

  @ExceptionHandler({ BoomerangException.class })
  public ResponseEntity<Object> handleBoomerangException(
      BoomerangException ex, WebRequest request) {
    
    RestErrorResponse errorResponse = new RestErrorResponse();
    errorResponse.setCode(ex.getCode());
    errorResponse.setReason(ex.getReason());
    if (ex.getMessage() == null || ex.getMessage().isBlank()) {
      try {
        errorResponse.setMessage(messageSource.getMessage(ex.getReason(), ex.getArgs(), Locale.ENGLISH));
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

    LOGGER.error("Exception["+errorResponse.getCode()+"] " + errorResponse.getReason() + " - " + errorResponse.getMessage());
    LOGGER.error(ExceptionUtils.getStackTrace(ex));
    
    return new ResponseEntity<>(
        errorResponse, new HttpHeaders(), ex.getStatus()); 
  }
}